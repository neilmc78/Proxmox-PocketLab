import dev.pocketlab.data.ProxmoxCluster;
import dev.pocketlab.data.SshAuthType;
import dev.pocketlab.data.SshHost;
import dev.pocketlab.data.SshKey;
import dev.pocketlab.docker.Container;
import dev.pocketlab.docker.DockerClient;
import dev.pocketlab.proxmox.ProxmoxClient;
import dev.pocketlab.ssh.HardenedSshConfig;
import dev.pocketlab.ssh.HostKeyException;
import dev.pocketlab.ssh.SlimCryptoProvider;
import dev.pocketlab.ssh.SshKt;
import dev.pocketlab.ssh.SshPool;
import kotlin.Pair;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

import java.io.File;
import java.nio.file.Files;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Drives the R8-shrunk app code (same rules as the release APK) against a real sshd and a fake
 * Proxmox API, to catch anything the shrinker removed that's only reached by reflection.
 * Args: sshdDir sshPort proxmoxBaseUrl proxmoxCertSha256 hostKeyFingerprint
 */
public class R8Probe {
    static int checks = 0;
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        checks++;
        System.out.println("ok  " + what);
    }

    static SshHost host(SshKey k, String fp, int port) {
        return new SshHost("h", "local", "127.0.0.1", port, System.getProperty("user.name"), SshAuthType.KEY, null, k.getId(), fp, true, false, null);
    }

    static String run(SSHClient c, String cmd) throws Exception {
        try (Session s = c.startSession()) {
            return new String(s.exec(cmd).getInputStream().readAllBytes());
        }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        int port = Integer.parseInt(a[1]);
        SlimCryptoProvider.Companion.install();
        check(Security.getProvider("BC") == null, "full BouncyCastle not registered");

        Pair<String, String> kp = SshKt.generateEd25519("probe");
        List<String> auth = new ArrayList<>();
        auth.add(kp.getSecond());
        for (String f : new String[]{"ed_pass", "rsa_openssh_pass", "rsa_pem", "ecdsa"})
            auth.add(Files.readString(new File(dir, "userkeys/" + f + ".pub").toPath()).trim());
        Files.writeString(new File(dir, "authorized_keys").toPath(), String.join("\n", auth) + "\n");
        SshKey key = new SshKey("k", "probe", kp.getFirst(), kp.getSecond(), null);
        List<SshKey> keys = List.of(key);

        try {
            SshPool.INSTANCE.open(host(key, null, port), keys, new HardenedSshConfig()).close();
            check(false, "unknown host key refused");
        } catch (HostKeyException e) {
            check(e.getFingerprint().equals(a[4]), "unknown host key refused, fingerprint matches ssh-keygen");
        }
        SshHost h = host(key, a[4], port);

        for (String kex : new String[]{"curve25519-sha256", "ecdh-sha2-nistp256", "ecdh-sha2-nistp521", "diffie-hellman-group16-sha512", "diffie-hellman-group-exchange-sha256"}) {
            HardenedSshConfig cfg = new HardenedSshConfig();
            cfg.setKeyExchangeFactories(cfg.getKeyExchangeFactories().stream().filter(f -> f.getName().equals(kex) || f.getName().startsWith("ext-info")).collect(Collectors.toList()));
            try (SSHClient c = SshPool.INSTANCE.open(h, keys, cfg)) { check(run(c, "echo ok").equals("ok\n"), "kex " + kex); }
        }
        List<String> ciphers = new HardenedSshConfig().getCipherFactories().stream().map(f -> f.getName()).collect(Collectors.toList());
        check(ciphers.equals(HardenedSshConfig.Companion.getPREFERRED_CIPHERS()), "only AEAD/CTR ciphers, chacha20 first: " + ciphers);
        for (String ci : new String[]{"chacha20-poly1305@openssh.com", "aes256-gcm@openssh.com", "aes128-ctr"}) {
            HardenedSshConfig cfg = new HardenedSshConfig();
            cfg.setCipherFactories(cfg.getCipherFactories().stream().filter(f -> f.getName().equals(ci)).collect(Collectors.toList()));
            try (SSHClient c = SshPool.INSTANCE.open(h, keys, cfg)) { check(run(c, "head -c 100000 /dev/zero | wc -c").trim().equals("100000"), "cipher " + ci); }
        }
        String[][] imported = {{"ed_pass", "hunter2"}, {"rsa_openssh_pass", "pw"}, {"rsa_pem", null}, {"ecdsa", null}};
        for (String[] im : imported) {
            SshKey k = new SshKey("i" + im[0], im[0], Files.readString(new File(dir, "userkeys/" + im[0]).toPath()), null, im[1]);
            try (SSHClient c = SshPool.INSTANCE.open(host(k, a[4], port), List.of(k), new HardenedSshConfig())) {
                check(run(c, "echo " + im[0]).equals(im[0] + "\n"), "imported key " + im[0]);
            }
        }

        DockerClient dc = new DockerClient(h, keys);
        @SuppressWarnings("unchecked")
        List<Container> cs = (List<Container>) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (s, cont) -> dc.list(cont));
        check(cs.size() == 2 && "lab".equals(cs.get(0).getComposeProject()), "docker ps JSON parsed: " + cs.size());

        try {
            proxmox(a);
        } catch (NoClassDefFoundError e) {
            if (!e.getMessage().startsWith("android/")) throw e;
            // R8 picks OkHttp's Android build, which needs a real device. Covered by ProxmoxClientTest instead.
            System.out.println("skip Proxmox (OkHttp's Android platform code can't run on a desktop JVM)");
        }
        System.out.println("R8 PROBE PASSED (" + checks + " checks)");
        System.exit(0);
    }

    static void proxmox(String[] a) throws Exception {
        ProxmoxCluster bad = new ProxmoxCluster("c", "lab", a[2], "me@pve!phone", "secret", null);
        try {
            BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (s, cont) -> new ProxmoxClient(bad).version(cont));
            check(false, "self-signed Proxmox cert refused without pin");
        } catch (Exception e) {
            check(dev.pocketlab.net.PinningTrustKt.untrustedCert(e) != null, "self-signed Proxmox cert refused without pin");
        }
        ProxmoxCluster good = new ProxmoxCluster("c", "lab", a[2], "me@pve!phone", "secret", a[3]);
        Object v = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (s, cont) -> new ProxmoxClient(good).version(cont));
        check("9.1.2".equals(v), "Proxmox API via pinned cert: " + v);
        Object res = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (s, cont) -> new ProxmoxClient(good).resources(cont));
        check(((Pair<?, ?>) res).getSecond().toString().contains("vmid=202"), "cluster resources parsed");
    }
}
