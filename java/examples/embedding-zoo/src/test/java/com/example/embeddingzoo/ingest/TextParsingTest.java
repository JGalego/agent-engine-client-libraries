package com.example.embeddingzoo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextParsingTest {

  @Test
  void hdfsLineKeepsTheBlockId() {
    HdfsLogLine line = HdfsLogLine.parse(
        "081109 203615 148 INFO dfs.DataNode$PacketResponder: PacketResponder 1 for block blk_38865049064139660 terminating");
    assertEquals("INFO", line.level);
    assertEquals("dfs.DataNode$PacketResponder", line.component);
    assertEquals("blk_38865049064139660", line.blockId);
  }

  @Test
  void htmlPrefersTheArticleBody() {
    String text = HtmlText.visibleText("<html><style>body{}</style><article><p>Load balancer state was restored.</p></article><p>toolbar</p></html>");
    assertTrue(text.contains("Load balancer state was restored."));
    assertTrue(!text.contains("toolbar") || text.indexOf("Load balancer") < text.indexOf("toolbar"));
  }

  @Test
  void mvtecLayoutReadsTheDirectoryNames() {
    MvtecIngestor.Layout layout = MvtecIngestor.layout("bottle/test/broken_large/000.png");
    assertEquals("bottle", layout.category());
    assertEquals("test", layout.split());
    assertEquals("broken_large", layout.label());
    assertTrue(layout.recognized());
  }
}
