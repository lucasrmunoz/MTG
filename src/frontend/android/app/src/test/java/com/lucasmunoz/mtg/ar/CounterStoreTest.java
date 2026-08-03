package com.lucasmunoz.mtg.ar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CounterStoreTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesAndReloadsCounters() throws Exception {
        CounterStore store = new CounterStore(folder.getRoot());
        CardCounters counters = store.get("printing-1");
        counters.addKeyword("Flying");
        counters.addStat(1, 1);
        counters.commanderCasts = 1;
        store.save();

        CounterStore reloaded = new CounterStore(folder.getRoot());
        CardCounters restored = reloaded.get("printing-1");
        assertEquals(1, restored.keywords.size());
        assertEquals(1, restored.stats.size());
        assertEquals(2, restored.commanderTax());
    }

    @Test
    public void exactlyOneStoreFileEverExists() throws Exception {
        CounterStore store = new CounterStore(folder.getRoot());
        store.get("a").addKeyword("Flying");
        store.save();
        store.get("b").addStat(2, 2);
        store.save();
        store.save();

        File[] files = folder.getRoot().listFiles();
        assertEquals(1, files.length);
        assertEquals("ar-counters.json", files[0].getName());
    }

    @Test
    public void emptyEntriesArePrunedFromDisk() throws Exception {
        CounterStore store = new CounterStore(folder.getRoot());
        CardCounters counters = store.get("soon-empty");
        counters.addStat(1, 1);
        store.save();
        counters.removeStat(1, 1);
        store.save();

        CounterStore reloaded = new CounterStore(folder.getRoot());
        assertTrue(reloaded.get("soon-empty").isEmpty());
        String contents = new String(
                Files.readAllBytes(new File(folder.getRoot(), "ar-counters.json").toPath()),
                StandardCharsets.UTF_8);
        assertFalse(contents.contains("soon-empty"));
    }

    @Test
    public void corruptFileIsDiscardedInsteadOfCrashing() throws Exception {
        File file = new File(folder.getRoot(), "ar-counters.json");
        Files.write(file.toPath(), "{not json at all".getBytes(StandardCharsets.UTF_8));

        CounterStore store = new CounterStore(folder.getRoot());
        assertTrue(store.get("anything").isEmpty());

        store.get("anything").addKeyword("Menace");
        store.save();
        CounterStore reloaded = new CounterStore(folder.getRoot());
        assertEquals(1, reloaded.get("anything").keywords.size());
    }
}
