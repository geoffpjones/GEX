package clob.app;

import clob.credit.CreditEngine;
import clob.engine.EngineConfig;
import clob.engine.MatchingEngine;
import clob.refdata.RefData;
import clob.sequencer.EngineDispatcher;
import clob.sequencer.InputCodec;
import clob.sequencer.Journal;
import clob.sequencer.OutputEncoder;
import clob.sequencer.OutputReader;
import clob.sequencer.Printers;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Prints a journal as readable inputs and, given reference data, the engine output each input produced,
 * by deterministic replay. This is the authoritative audit trail: the journal plus replay reproduces
 * exactly what happened, including lottery draws.
 */
public final class AuditTool {
    private AuditTool() {}

    public static void print(Path journalDir, Path refDataFile, int maxAccounts, long seed, PrintStream out) throws IOException {
        if (refDataFile == null) {
            Journal.replay(journalDir, (b, o, l) -> out.println(Printers.input(b, o)));
            return;
        }
        RefData refData = RefDataLoader.load(refDataFile);
        OutputReader reader = new OutputReader();
        EngineConfig d = EngineConfig.defaults();
        MatchingEngine engine = new MatchingEngine(refData, new EngineConfig(d.initialOrders(), d.maxLiveOrders(),
                d.initialLevels(), d.initialLevelsPerSide(), d.initialTimers(), seed), new CreditEngine(maxAccounts, refData),
                new OutputEncoder((buf, off, len) -> {
                    reader.read(buf, off);
                    out.println("    -> " + Printers.output(reader));
                }));
        EngineDispatcher dispatcher = new EngineDispatcher(engine, (s, t, ty, r) -> out.println("    !! refused: " + r));
        Journal.replay(journalDir, (b, o, l) -> {
            if (InputCodec.type(b, o) != InputCodec.MD_TICK && InputCodec.type(b, o) != InputCodec.TIME_ADVANCE) {
                out.println(Printers.input(b, o));
            }
            dispatcher.onInput(b, o, l);
        });
    }
}
