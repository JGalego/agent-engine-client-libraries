package com.example.embeddingzoo.ingest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One line of the Loghub HDFS sample. Fields that are absent stay empty. */
public final class HdfsLogLine {

  private static final Pattern LINE = Pattern.compile(
      "^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(INFO|WARN|ERROR|DEBUG|FATAL|TRACE)\\s+(\\S+?):\\s*(.*)$");
  private static final Pattern BLOCK = Pattern.compile("blk_-?\\d+");

  public final String date;
  public final String time;
  public final String pid;
  public final String level;
  public final String component;
  public final String message;
  public final String blockId;

  private HdfsLogLine(String date, String time, String pid, String level, String component, String message, String blockId) {
    this.date = date;
    this.time = time;
    this.pid = pid;
    this.level = level;
    this.component = component;
    this.message = message;
    this.blockId = blockId;
  }

  public static HdfsLogLine parse(String line) {
    Matcher matcher = LINE.matcher(line);
    Matcher block = BLOCK.matcher(line);
    String blockId = block.find() ? block.group() : "";
    if (!matcher.matches()) {
      return new HdfsLogLine("", "", "", "", "", line, blockId);
    }
    return new HdfsLogLine(
        matcher.group(1),
        matcher.group(2),
        matcher.group(3),
        matcher.group(4),
        matcher.group(5),
        matcher.group(6),
        blockId);
  }
}
