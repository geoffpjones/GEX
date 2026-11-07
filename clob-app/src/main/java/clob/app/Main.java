package clob.app;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * {@code clob primary <node.properties>} | {@code clob standby <node.properties>} |
 * {@code clob audit <node.properties>} (prints the journal with replayed engine output).
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: clob primary|standby|audit <node.properties>");
            System.exit(2);
        }
        NodeConfig cfg = NodeConfig.load(Path.of(args[1]));
        switch (args[0]) {
            case "primary" -> {
                ClobNode node = ClobNode.startPrimary(cfg);
                System.out.printf("primary up: FIX %d, admin %d, replication %d%n", node.fixPort(), node.adminPort(),
                        node.replicationPort());
                awaitShutdown(node);
            }
            case "standby" -> {
                StandbyNode node = new StandbyNode(cfg);
                System.out.printf("standby following %s:%d, admin %d (POST /admin/promote to take over)%n",
                        cfg.primaryHost(), cfg.primaryReplicationPort(), node.adminPort());
                awaitShutdown(node);
            }
            case "audit" -> AuditTool.print(cfg.journalDir(), cfg.refDataFile(), cfg.maxAccounts(), cfg.randomSeed(), System.out);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void awaitShutdown(AutoCloseable node) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                node.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            done.countDown();
        }));
        done.await();
    }
}
