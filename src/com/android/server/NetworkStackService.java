/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.net.dhcp.IDhcpServer.STATUS_INVALID_ARGUMENT;
import static android.net.dhcp.IDhcpServer.STATUS_SUCCESS;
import static android.net.dhcp.IDhcpServer.STATUS_UNKNOWN_ERROR;
import static android.net.util.RawSocketUtils.sendRawPacketDownStream;

import static com.android.net.module.util.DeviceConfigUtils.getResBooleanConfig;
import static com.android.net.module.util.FeatureVersions.FEATURE_IS_UID_NETWORKING_BLOCKED;
import static com.android.networkstack.util.NetworkStackUtils.IGNORE_TCP_INFO_FOR_BLOCKED_UIDS;
import static com.android.networkstack.util.NetworkStackUtils.SKIP_TCP_POLL_IN_LIGHT_DOZE;
import static com.android.server.util.PermissionUtil.checkDumpPermission;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.IIpMemoryStore;
import android.net.IIpMemoryStoreCallbacks;
import android.net.INetd;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkStackConnector;
import android.net.INetworkStackStatusCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.PrivateDnsConfigParcel;
import android.net.dhcp.DhcpServer;
import android.net.dhcp.DhcpServingParams;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.ip.IIpClientCallbacks;
import android.net.ip.IpClient;
import android.net.networkstack.aidl.NetworkMonitorParameters;
import android.net.shared.PrivateDnsConfig;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.BasicShellCommandHandler;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.SharedLog;
import com.android.networkstack.NetworkStackNotifier;
import com.android.networkstack.R;
import com.android.networkstack.apishim.common.ShimUtils;
import com.android.networkstack.ipmemorystore.IpMemoryStoreService;
import com.android.server.connectivity.NetworkMonitor;
import com.android.server.util.PermissionUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Android service used to start the network stack when bound to via an intent.
 *
 * <p>The service returns a binder for the system server to communicate with the network stack.
 */
public class NetworkStackService extends Service {
    private static final String TAG = NetworkStackService.class.getSimpleName();
    private static NetworkStackConnector sConnector;

    /**
     * Create a binder connector for the system server to communicate with the network stack.
     *
     * <p>On platforms where the network stack runs in the system server process, this method may
     * be called directly instead of obtaining the connector by binding to the service.
     */
    public static synchronized IBinder makeConnector(Context context) {
        if (sConnector == null) {
            sConnector = new NetworkStackConnector(context);
        }
        return sConnector;
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        return makeConnector(this);
    }

    /**
     * An interface for internal clients of the network stack service that can return
     * or create inline instances of the service it manages.
     */
    public interface NetworkStackServiceManager {
        /**
         * Get an instance of the IpMemoryStoreService.
         */
        IIpMemoryStore getIpMemoryStoreService();

        /**
         * Get an instance of the NetworkNotifier.
         */
        NetworkStackNotifier getNotifier();
    }

    /**
     * Permission checking dependency of the connector, useful for testing.
     */
    public static class PermissionChecker {
        /**
         * @see PermissionUtil#enforceNetworkStackCallingPermission()
         */
        public void enforceNetworkStackCallingPermission() {
            PermissionUtil.enforceNetworkStackCallingPermission();
        }
    }

    /**
     * Dependencies of {@link NetworkStackConnector}, useful for testing.
     */
    public static class Dependencies {
        /** @see IpMemoryStoreService */
        @NonNull
        public IpMemoryStoreService makeIpMemoryStoreService(@NonNull Context context) {
            return new IpMemoryStoreService(context);
        }

        /** @see NetworkStackNotifier */
        @NonNull
        public NetworkStackNotifier makeNotifier(@NonNull Context context, @NonNull Looper looper) {
            return new NetworkStackNotifier(context, looper);
        }

        /** @see DhcpServer */
        @NonNull
        public DhcpServer makeDhcpServer(@NonNull Context context, @NonNull String ifName,
                @NonNull DhcpServingParams params, @NonNull SharedLog log) {
            return new DhcpServer(context, ifName, params, log);
        }

        /** @see NetworkMonitor */
        @NonNull
        public NetworkMonitor makeNetworkMonitor(@NonNull Context context,
                @NonNull INetworkMonitorCallbacks cb, @NonNull Network network,
                @NonNull SharedLog log, @NonNull NetworkStackServiceManager nsServiceManager) {
            return new NetworkMonitor(context, cb, network, log, nsServiceManager);
        }

        /** @see IpClient */
        @NonNull
        public IpClient makeIpClient(@NonNull Context context, @NonNull String ifName,
                @NonNull IIpClientCallbacks cb,
                @NonNull NetworkStackServiceManager nsServiceManager) {
            return new IpClient(context, ifName, cb, nsServiceManager);
        }
    }

    /**
     * Connector implementing INetworkStackConnector for clients.
     */
    @VisibleForTesting
    public static class NetworkStackConnector extends INetworkStackConnector.Stub
            implements NetworkStackServiceManager {
        private static final int NUM_VALIDATION_LOG_LINES = 20;
        private final Context mContext;
        private final PermissionChecker mPermChecker;
        private final Dependencies mDeps;
        private final INetd mNetd;
        @GuardedBy("mIpClients")
        private final ArrayList<WeakReference<IpClient>> mIpClients = new ArrayList<>();
        private final IpMemoryStoreService mIpMemoryStoreService;
        @Nullable
        private final NetworkStackNotifier mNotifier;

        private static final int MAX_VALIDATION_LOGS = 10;
        @GuardedBy("mValidationLogs")
        private final ArrayDeque<SharedLog> mValidationLogs = new ArrayDeque<>(MAX_VALIDATION_LOGS);

        private static final String DUMPSYS_ARG_VERSION = "version";

        private static final String AIDL_KEY_NETWORKSTACK = "networkstack";
        private static final String AIDL_KEY_IPMEMORYSTORE = "ipmemorystore";
        private static final String AIDL_KEY_NETD = "netd";

        private static final int VERSION_UNKNOWN = -1;
        private static final String HASH_UNKNOWN = "unknown";

        /**
         * Versions of the AIDL interfaces observed by the network stack, in other words versions
         * that the framework and other modules communicating with the network stack are using.
         * The map may hold multiple values as the interface is used by modules with different
         * versions.
         */
        @GuardedBy("mFrameworkAidlVersions")
        private final ArraySet<AidlVersion> mAidlVersions = new ArraySet<>();

        private static final class AidlVersion implements Comparable<AidlVersion> {
            @NonNull
            final String mKey;
            final int mVersion;
            @NonNull
            final String mHash;

            private static final Comparator<AidlVersion> COMPARATOR =
                    Comparator.comparing((AidlVersion v) -> v.mKey)
                            .thenComparingInt(v -> v.mVersion)
                            .thenComparing(v -> v.mHash, String::compareTo);

            AidlVersion(@NonNull String key, int version, @NonNull String hash) {
                mKey = key;
                mVersion = version;
                mHash = hash;
            }

            @Override
            public int hashCode() {
                return Objects.hash(mVersion, mHash);
            }

            @Override
            public boolean equals(@Nullable Object obj) {
                if (!(obj instanceof AidlVersion)) return false;
                final AidlVersion other = (AidlVersion) obj;
                return Objects.equals(mKey, other.mKey)
                        && Objects.equals(mVersion, other.mVersion)
                        && Objects.equals(mHash, other.mHash);
            }

            @NonNull
            @Override
            public String toString() {
                // Use a format that can be easily parsed by tests for the version
                return String.format("%s:%s:%s", mKey, mVersion, mHash);
            }

            @Override
            public int compareTo(AidlVersion o) {
                return COMPARATOR.compare(this, o);
            }
        }

        private SharedLog addValidationLogs(Network network, String name) {
            final SharedLog log = new SharedLog(NUM_VALIDATION_LOG_LINES, network + " - " + name);
            synchronized (mValidationLogs) {
                while (mValidationLogs.size() >= MAX_VALIDATION_LOGS) {
                    mValidationLogs.removeLast();
                }
                mValidationLogs.addFirst(log);
            }
            return log;
        }

        NetworkStackConnector(@NonNull Context context) {
            this(context, new PermissionChecker(), new Dependencies());
        }

        @VisibleForTesting
        public NetworkStackConnector(
                @NonNull Context context, @NonNull PermissionChecker permChecker,
                @NonNull Dependencies deps) {
            mContext = context;
            mPermChecker = permChecker;
            mDeps = deps;
            mNetd = INetd.Stub.asInterface(
                    (IBinder) context.getSystemService(Context.NETD_SERVICE));
            mIpMemoryStoreService = mDeps.makeIpMemoryStoreService(context);
            // NetworkStackNotifier only shows notifications relevant for API level > Q
            if (ShimUtils.isReleaseOrDevelopmentApiAbove(Build.VERSION_CODES.Q)) {
                final HandlerThread notifierThread = new HandlerThread(
                        NetworkStackNotifier.class.getSimpleName());
                notifierThread.start();
                mNotifier = mDeps.makeNotifier(context, notifierThread.getLooper());
            } else {
                mNotifier = null;
            }

            int netdVersion;
            String netdHash;
            try {
                netdVersion = mNetd.getInterfaceVersion();
                netdHash = mNetd.getInterfaceHash();
            } catch (RemoteException e) {
                mLog.e("Error obtaining INetd version", e);
                netdVersion = VERSION_UNKNOWN;
                netdHash = HASH_UNKNOWN;
            }
            updateNetdAidlVersion(netdVersion, netdHash);
        }

        private void updateNetdAidlVersion(final int version, final String hash) {
            synchronized (mAidlVersions) {
                mAidlVersions.add(new AidlVersion(AIDL_KEY_NETD, version, hash));
            }
        }

        private void updateNetworkStackAidlVersion(final int version, final String hash) {
            synchronized (mAidlVersions) {
                mAidlVersions.add(new AidlVersion(AIDL_KEY_NETWORKSTACK, version, hash));
            }
        }

        private void updateIpMemoryStoreAidlVersion(final int version, final String hash) {
            synchronized (mAidlVersions) {
                mAidlVersions.add(new AidlVersion(AIDL_KEY_IPMEMORYSTORE, version, hash));
            }
        }

        @NonNull
        private final SharedLog mLog = new SharedLog(TAG);

        @Override
        public void makeDhcpServer(@NonNull String ifName, @NonNull DhcpServingParamsParcel params,
                @NonNull IDhcpServerCallbacks cb) throws RemoteException {
            mPermChecker.enforceNetworkStackCallingPermission();
            updateNetworkStackAidlVersion(cb.getInterfaceVersion(), cb.getInterfaceHash());
            final DhcpServer server;
            try {
                server = mDeps.makeDhcpServer(
                        mContext,
                        ifName,
                        DhcpServingParams.fromParcelableObject(params),
                        mLog.forSubComponent(ifName + ".DHCP"));
            } catch (DhcpServingParams.InvalidParameterException e) {
                mLog.e("Invalid DhcpServingParams", e);
                cb.onDhcpServerCreated(STATUS_INVALID_ARGUMENT, null);
                return;
            } catch (Exception e) {
                mLog.e("Unknown error starting DhcpServer", e);
                cb.onDhcpServerCreated(STATUS_UNKNOWN_ERROR, null);
                return;
            }
            cb.onDhcpServerCreated(STATUS_SUCCESS, server.makeConnector());
        }

        @Override
        public void makeNetworkMonitor(Network network, String name, INetworkMonitorCallbacks cb)
                throws RemoteException {
            mPermChecker.enforceNetworkStackCallingPermission();
            updateNetworkStackAidlVersion(cb.getInterfaceVersion(), cb.getInterfaceHash());
            final SharedLog log = addValidationLogs(network, name);
            final NetworkMonitor nm = mDeps.makeNetworkMonitor(mContext, cb, network, log, this);
            cb.onNetworkMonitorCreated(new NetworkMonitorConnector(nm, mPermChecker));
        }

        @Override
        public void makeIpClient(String ifName, IIpClientCallbacks cb) throws RemoteException {
            mPermChecker.enforceNetworkStackCallingPermission();
            updateNetworkStackAidlVersion(cb.getInterfaceVersion(), cb.getInterfaceHash());
            final IpClient ipClient = mDeps.makeIpClient(
                    mContext, ifName, cb, this);

            synchronized (mIpClients) {
                final Iterator<WeakReference<IpClient>> it = mIpClients.iterator();
                while (it.hasNext()) {
                    final IpClient ipc = it.next().get();
                    if (ipc == null) {
                        it.remove();
                    }
                }
                mIpClients.add(new WeakReference<>(ipClient));
            }

            cb.onIpClientCreated(ipClient.makeConnector());
        }

        @Override
        public IIpMemoryStore getIpMemoryStoreService() {
            return mIpMemoryStoreService;
        }

        @Override
        public NetworkStackNotifier getNotifier() {
            return mNotifier;
        }

        @Override
        public void fetchIpMemoryStore(@NonNull final IIpMemoryStoreCallbacks cb)
                throws RemoteException {
            mPermChecker.enforceNetworkStackCallingPermission();
            updateIpMemoryStoreAidlVersion(cb.getInterfaceVersion(), cb.getInterfaceHash());
            cb.onIpMemoryStoreFetched(mIpMemoryStoreService);
        }

        @Override
        public void allowTestUid(int uid, @Nullable INetworkStackStatusCallback cb)
                throws RemoteException {
            // setTestUid does its own permission checks
            PermissionUtil.setTestUid(mContext, uid);
            mLog.i("Allowing test uid " + uid);
            if (cb != null) cb.onStatusAvailable(0);
        }

        @Override @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
                @Nullable String[] args) {
            checkDumpPermission();

            final IndentingPrintWriter pw = new IndentingPrintWriter(fout, "  ");
            pw.println("NetworkStack version:");
            dumpVersion(pw);
            pw.println();

            if (args != null && args.length >= 1 && DUMPSYS_ARG_VERSION.equals(args[0])) {
                return;
            }

            pw.println("Device Configs:");
            pw.increaseIndent();
            pw.println("SKIP_TCP_POLL_IN_LIGHT_DOZE="
                    + DeviceConfigUtils.isNetworkStackFeatureNotChickenedOut(
                            mContext, SKIP_TCP_POLL_IN_LIGHT_DOZE));
            pw.println("FEATURE_IS_UID_NETWORKING_BLOCKED=" + DeviceConfigUtils.isFeatureSupported(
                            mContext, FEATURE_IS_UID_NETWORKING_BLOCKED));
            pw.println("IGNORE_TCP_INFO_FOR_BLOCKED_UIDS="
                    + DeviceConfigUtils.isNetworkStackFeatureNotChickenedOut(mContext,
                            IGNORE_TCP_INFO_FOR_BLOCKED_UIDS));
            pw.decreaseIndent();
            pw.println();


            pw.println("NetworkStack logs:");
            mLog.dump(fd, pw, args);

            // Dump full IpClient logs for non-GCed clients
            pw.println();
            pw.println("Recently active IpClient logs:");
            final ArrayList<IpClient> ipClients = new ArrayList<>();
            final HashSet<String> dumpedIpClientIfaces = new HashSet<>();
            synchronized (mIpClients) {
                for (WeakReference<IpClient> ipcRef : mIpClients) {
                    final IpClient ipc = ipcRef.get();
                    if (ipc != null) {
                        ipClients.add(ipc);
                    }
                }
            }

            for (IpClient ipc : ipClients) {
                pw.println(ipc.getName());
                pw.increaseIndent();
                ipc.dump(fd, pw, args);
                pw.decreaseIndent();
                dumpedIpClientIfaces.add(ipc.getInterfaceName());
            }

            // State machine and connectivity metrics logs are kept for GCed IpClients
            pw.println();
            pw.println("Other IpClient logs:");
            IpClient.dumpAllLogs(fout, dumpedIpClientIfaces);

            pw.println();
            pw.println("Validation logs (most recent first):");
            synchronized (mValidationLogs) {
                for (SharedLog p : mValidationLogs) {
                    pw.println(p.getTag());
                    pw.increaseIndent();
                    p.dump(fd, pw, args);
                    pw.decreaseIndent();
                }
            }

            pw.println();
            pw.print("useNeighborResource: ");
            pw.println(getResBooleanConfig(mContext,
                    R.bool.config_no_sim_card_uses_neighbor_mcc, false));
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return new ShellCmd().exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                    err.getFileDescriptor(), args);
        }

        private String apfShellCommand(String iface, String cmd, @Nullable String optarg) {
            synchronized (mIpClients) {
                // HACK: An old IpClient serving the given interface name might not have been
                // garbage collected. Since new IpClients are always appended to the list, iterate
                // through it in reverse order to get the most up-to-date IpClient instance.
                // Create a ListIterator at the end of the list.
                final ListIterator it = mIpClients.listIterator(mIpClients.size());
                while (it.hasPrevious()) {
                    final IpClient ipClient = ((WeakReference<IpClient>) it.previous()).get();
                    if (ipClient != null && ipClient.getInterfaceName().equals(iface)) {
                        return ipClient.apfShellCommand(cmd, optarg);
                    }
                }
            }
            throw new IllegalArgumentException("No active IpClient found for interface " + iface);
        }

        private class ShellCmd extends BasicShellCommandHandler {
            @Override
            public int onCommand(String cmd) {
                if (cmd == null) {
                    return handleDefaultCommands(cmd);
                }
                final PrintWriter pw = getOutPrintWriter();
                switch (cmd) {
                    case "is-uid-networking-blocked":
                        if (!DeviceConfigUtils.isFeatureSupported(mContext,
                                FEATURE_IS_UID_NETWORKING_BLOCKED)) {
                            throw new IllegalStateException("API is unsupported");
                        }

                        // Usage : cmd network_stack is-uid-networking-blocked <uid> <metered>
                        // If no argument, get and display the usage help.
                        if (getRemainingArgsCount() != 2) {
                            onHelp();
                            throw new IllegalArgumentException("Incorrect number of arguments");
                        }
                        final int uid;
                        final boolean metered;
                        uid = Integer.parseInt(getNextArg());
                        metered = Boolean.parseBoolean(getNextArg());
                        final ConnectivityManager cm =
                                mContext.getSystemService(ConnectivityManager.class);
                        pw.println(cm.isUidNetworkingBlocked(uid, metered /* isNetworkMetered */));
                        return 0;
                    case "send-raw-packet-downstream": {
                        // Usage : cmd network_stack send-raw-packet-downstream
                        //         <interface> <packet-in-hex>
                        // If no argument, get and display the usage help.
                        if (getRemainingArgsCount() != 2) {
                            onHelp();
                            throw new IllegalArgumentException("Incorrect number of arguments");
                        }
                        final String iface = getNextArg();
                        final String packetInHex = getNextArg();
                        try {
                            sendRawPacketDownStream(mContext, iface, packetInHex);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        return 0;
                    }
                    case "apf":
                        // Usage: cmd network_stack apf <iface> <cmd>
                        final String iface = getNextArg();
                        if (iface == null) {
                            throw new IllegalArgumentException("No <iface> specified");
                        }

                        final String subcmd = getNextArg();
                        if (subcmd == null) {
                            throw new IllegalArgumentException("No <cmd> specified");
                        }

                        final String optarg = getNextArg();
                        if (getRemainingArgsCount() != 0) {
                            throw new IllegalArgumentException("Too many arguments passed");
                        }

                        final String result = apfShellCommand(iface, subcmd, optarg);
                        pw.println(result);
                        return 0;

                    default:
                        return handleDefaultCommands(cmd);
                }
            }

            @Override
            public void onHelp() {
                PrintWriter pw = getOutPrintWriter();
                pw.println("NetworkStack service commands:");
                pw.println("  help");
                pw.println("    Print this help text.");
                pw.println("  is-uid-networking-blocked <uid> <metered>");
                pw.println("    Get whether the networking is blocked for given uid and metered.");
                pw.println("    <uid>: The target uid.");
                pw.println("    <metered>: [true|false], Whether the target network is metered.");
                pw.println("  send-raw-packet-downstream <interface> <packet-in-hex>");
                pw.println("    Send raw packet for testing purpose.");
                pw.println("    <interface>: Target interface name, note that this is limited");
                pw.println("      to tethering downstream for security considerations.");
                pw.println("    <packet_in_hex>: A valid hexadecimal representation of ");
                pw.println("      a packet starting from L2 header.");
                pw.println("  apf <iface> <cmd>");
                pw.println("    APF utility commands for integration tests.");
                pw.println("    <iface>: the network interface the provided command operates on.");
                pw.println("    <cmd>: [status]");
                pw.println("      status");
                pw.println("        returns whether the APF filter is \"running\" or \"paused\".");
                pw.println("      pause");
                pw.println("        pause APF filter generation.");
                pw.println("      resume");
                pw.println("        resume APF filter generation.");
                pw.println("      install <program-hex-string>");
                pw.println("        install the APF program contained in <program-hex-string>.");
                pw.println("        The filter must be paused before installing a new program.");
                pw.println("      capabilities");
                pw.println("        return the reported APF capabilities.");
                pw.println("        Format: <apfVersion>,<maxProgramSize>,<packetFormat>");
                pw.println("      read");
                pw.println("        reads and returns the current state of APF memory.");
            }
        }

        /**
         * Dump version information of the module and detected system version.
         */
        private void dumpVersion(@NonNull PrintWriter fout) {
            if (!ShimUtils.isReleaseOrDevelopmentApiAbove(Build.VERSION_CODES.Q)) {
                dumpVersionNumberOnly(fout);
                return;
            }

            fout.println("LocalInterface:" + this.VERSION + ":" + this.HASH);
            synchronized (mAidlVersions) {
                // Sort versions for deterministic order in output
                for (AidlVersion version : sortVersions(mAidlVersions)) {
                    fout.println(version);
                }
            }
        }

        private List<AidlVersion> sortVersions(Collection<AidlVersion> versions) {
            final List<AidlVersion> sorted = new ArrayList<>(versions);
            Collections.sort(sorted);
            return sorted;
        }

        /**
         * Legacy version of dumpVersion, only used for Q, as only the interface version number
         * was used in Q.
         *
         * <p>Q behavior needs to be preserved as conformance tests for Q still expect this format.
         * Once all conformance test suites are updated to expect the new format even on Q devices,
         * this can be removed.
         */
        private void dumpVersionNumberOnly(@NonNull PrintWriter fout) {
            fout.println("NetworkStackConnector: " + this.VERSION);
            final SortedSet<Integer> systemServerVersions = new TreeSet<>();
            int netdVersion = VERSION_UNKNOWN;
            synchronized (mAidlVersions) {
                for (AidlVersion version : mAidlVersions) {
                    switch (version.mKey) {
                        case AIDL_KEY_IPMEMORYSTORE:
                        case AIDL_KEY_NETWORKSTACK:
                            systemServerVersions.add(version.mVersion);
                            break;
                        case AIDL_KEY_NETD:
                            netdVersion = version.mVersion;
                            break;
                        default:
                            break;
                    }
                }
            }
            // TreeSet.toString is formatted as [a, b], but Q used ArraySet.toString formatted as
            // {a, b}. ArraySet does not have guaranteed ordering, which was not a problem in Q
            // when only one interface number was expected (and there was no unit test relying on
            // the ordering).
            fout.println("SystemServer: {" + TextUtils.join(", ", systemServerVersions) + "}");
            fout.println("Netd: " + netdVersion);
        }

        /**
         * Get the version of the AIDL interface.
         */
        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }

    /**
     * Proxy for {@link NetworkMonitor} that implements {@link INetworkMonitor}.
     */
    @VisibleForTesting
    public static class NetworkMonitorConnector extends INetworkMonitor.Stub {
        @NonNull
        private final NetworkMonitor mNm;
        @NonNull
        private final PermissionChecker mPermChecker;

        public NetworkMonitorConnector(@NonNull NetworkMonitor nm,
                @NonNull PermissionChecker permChecker) {
            mNm = nm;
            mPermChecker = permChecker;
        }

        @Override
        public void start() {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.start();
        }

        @Override
        public void launchCaptivePortalApp() {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.launchCaptivePortalApp();
        }

        @Override
        public void notifyCaptivePortalAppFinished(int response) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyCaptivePortalAppFinished(response);
        }

        @Override
        public void setAcceptPartialConnectivity() {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.setAcceptPartialConnectivity();
        }

        @Override
        public void forceReevaluation(int uid) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.forceReevaluation(uid);
        }

        @Override
        public void notifyPrivateDnsChanged(PrivateDnsConfigParcel config) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyPrivateDnsSettingsChanged(PrivateDnsConfig.fromParcel(config));
        }

        @Override
        public void notifyDnsResponse(int returnCode) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyDnsResponse(returnCode);
        }

        /**
         * Send a notification to NetworkMonitor indicating that the network is now connected.
         * @Deprecated use notifyNetworkConnectedParcel, which also passes the NetworkAgentConfig.
         */
        @Override
        public void notifyNetworkConnected(LinkProperties lp, NetworkCapabilities nc) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyNetworkConnected(lp, nc);
        }

        /**
         * Send a notification to NetworkMonitor indicating that the network is now connected.
         */
        @Override
        public void notifyNetworkConnectedParcel(NetworkMonitorParameters params) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyNetworkConnectedParcel(params);
        }

        @Override
        public void notifyNetworkDisconnected() {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyNetworkDisconnected();
        }

        @Override
        public void notifyLinkPropertiesChanged(LinkProperties lp) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyLinkPropertiesChanged(lp);
        }

        @Override
        public void notifyNetworkCapabilitiesChanged(NetworkCapabilities nc) {
            mPermChecker.enforceNetworkStackCallingPermission();
            mNm.notifyNetworkCapabilitiesChanged(nc);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }
}
