package com.taobao.arthas.core.command.monitor200;

import java.io.File;
import java.io.IOException;
import java.security.CodeSource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.taobao.arthas.common.OSUtils;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.shell.cli.CliToken;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.DefaultValue;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;
import com.taobao.middleware.logger.Logger;

import one.profiler.AsyncProfiler;
import one.profiler.Counter;

/**
 * 
 * @author hengyunabc 2019-10-31
 *
 */
@Name("profiler")
@Summary("Profiler")
@Description("\nExamples:\n" + "  mbean\n" + "  mbean -m java.lang:type=Threading\n"
        + "  mbean java.lang:type=Threading\n" + "  mbean java.lang:type=Threading *Count\n"
        + "  mbean -E java.lang:type=Threading PeakThreadCount|ThreadCount|DaemonThreadCount\n"
        + "  mbean -i 1000 java.lang:type=Threading *Count\n" + Constants.WIKI + Constants.WIKI_HOME + "profiler")
public class ProfilerCommand extends AnnotatedCommand {
    private static final Logger logger = LogUtil.getArthasLogger();

    private String action;
    private String actionArg;

    private String event;

    private String file;
    /**
     * output file format, default value is svg.
     */
    private String format;

    /**
     * sampling interval in ns (default: 10'000'000, i.e. 10 ms)
     */
    private Long interval;

    private boolean threads;

    private static String libPath;
    private static AsyncProfiler profiler = null;

    static {
        String profierSoPath = null;
        if (OSUtils.isMac()) {
            profierSoPath = "async-profiler/libasyncProfiler-mac-x64.so";
        }
        if (OSUtils.isLinux()) {
            profierSoPath = "async-profiler/libasyncProfiler-linux-x64.so";
        }

        if (profierSoPath != null) {
            CodeSource codeSource = ProfilerCommand.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                try {
                    File bootJarPath = new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
                    File soFile = new File(bootJarPath.getParentFile(), profierSoPath);
                    if (soFile.exists()) {
                        libPath = soFile.getAbsolutePath();
                    }
                } catch (Throwable e) {
                    logger.error("arthas", "can not find libasyncProfiler so", e);
                }
            }
        }

    }

    @Argument(argName = "action", index = 0, required = true)
    @Description("Action to execute")
    public void setAction(String action) {
        this.action = action;
    }

    @Argument(argName = "actionArg", index = 1, required = false)
    @Description("Attribute name pattern.")
    public void setActionArg(String actionArg) {
        this.actionArg = actionArg;
    }

    @Option(shortName = "i", longName = "interval")
    @Description("sampling interval in ns (default: 10'000'000, i.e. 10 ms)")
    @DefaultValue("10000000")
    public void setInterval(long interval) {
        this.interval = interval;
    }

    @Option(shortName = "f", longName = "file")
    @Description("dump output to <filename>")
    public void setFile(String file) {
        this.file = file;
    }

    @Option(longName = "format")
    @Description("dump output file format(svg, html, jfr), default valut is svg")
    @DefaultValue("svg")
    public void setFormat(String format) {
        this.format = format;
    }

    @Option(shortName = "e", longName = "event")
    @Description("which event to trace (cpu, alloc, lock, cache-misses etc.), default value is cpu")
    @DefaultValue("cpu")
    public void setEvent(String event) {
        this.event = event;
    }

    @Option(longName = "threads")
    @Description("profile different threads separately")
    public void setThreads(boolean threads) {
        this.threads = threads;
    }

    private AsyncProfiler profilerInstance() {
        if (profiler != null) {
            return profiler;
        }

        // try to load from special path
        if ("load".equals(action)) {
            profiler = AsyncProfiler.getInstance(this.actionArg);
        }

        if (libPath != null) {
            // load from arthas directory
            profiler = AsyncProfiler.getInstance(libPath);
        } else {
            if (OSUtils.isLinux() || OSUtils.isLinux()) {
                throw new IllegalStateException("Can not find libasyncProfiler so, please check the arthas directory.");
            } else {
                throw new IllegalStateException("Current OS do not support AsyncProfiler.");
            }
        }

        return profiler;
    }

    enum ProfilerAction {
        execute, start, stop, resume, list, version, status,

        dumpCollapsed, dumpFlat, dumpTraces, getSamples,
    }

    private String executeArgs(ProfilerAction action) {
        StringBuilder sb = new StringBuilder();

        // start - start profiling
        // resume - start or resume profiling without resetting collected data
        // stop - stop profiling
        sb.append(action).append(',');

        if (this.event != null) {
            sb.append("event=").append(this.event).append(',');
        }
        if (this.file != null) {
            sb.append("file=").append(this.file).append(',');
        }
        if (this.interval != null) {
            sb.append("interval=").append(this.interval).append(',');
        }
        if (this.threads) {
            sb.append("threads").append(',');
        }

        return sb.toString();
    }

    private static String execute(AsyncProfiler asyncProfiler, String arg)
            throws IllegalArgumentException, IOException {
        String result = asyncProfiler.execute(arg);
        if (!result.endsWith("\n")) {
            result += "\n";
        }
        return result;
    }

    @Override
    public void process(CommandProcess process) {
        int status = 0;
        try {
            AsyncProfiler asyncProfiler = this.profilerInstance();

            ProfilerAction profilerAction = ProfilerAction.valueOf(action);

            if (ProfilerAction.execute.equals(profilerAction)) {
                String result = execute(asyncProfiler, this.actionArg);
                process.write(result);
            } else if (ProfilerAction.start.equals(profilerAction)) {
                String executeArgs = executeArgs(ProfilerAction.start);
                String result = execute(asyncProfiler, executeArgs);
                process.write(result);
            } else if (ProfilerAction.stop.equals(profilerAction)) {
                if (this.file == null) {
                    this.file = new File("arthas-output",
                            new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "." + this.format)
                                    .getAbsolutePath();
                }
                process.write("profiler output file: " + new File(this.file).getAbsolutePath() + "\n");
                String executeArgs = executeArgs(ProfilerAction.stop);
                String result = execute(asyncProfiler, executeArgs);
                process.write(result);
            } else if (ProfilerAction.resume.equals(profilerAction)) {
                String executeArgs = executeArgs(ProfilerAction.resume);
                String result = execute(asyncProfiler, executeArgs);
                process.write(result);
            } else if (ProfilerAction.list.equals(profilerAction)) {
                String result = asyncProfiler.execute("list");
                process.write(result);
            } else if (ProfilerAction.version.equals(profilerAction)) {
                String result = asyncProfiler.execute("version");
                process.write(result);
            } else if (ProfilerAction.status.equals(profilerAction)) {
                String result = asyncProfiler.execute("status");
                process.write(result);
            } else if (ProfilerAction.dumpCollapsed.equals(profilerAction)) {
                if (actionArg == null) {
                    actionArg = "TOTAL";
                }
                actionArg = actionArg.toUpperCase();
                System.err.println("actionArg: " + actionArg + ", " + "TOTAL".equals(actionArg));
                if ("TOTAL".equals(actionArg) || "SAMPLES".equals(actionArg)) {
                    String result = asyncProfiler.dumpCollapsed(Counter.valueOf(actionArg));
                    process.write(result);
                } else {
                    process.write("ERROR: dumpCollapsed argumment should be TOTAL or SAMPLES. \n");
                    status = 1;
                }
            } else if (ProfilerAction.dumpFlat.equals(profilerAction)) {
                int maxMethods = 0;
                if (actionArg != null) {
                    maxMethods = Integer.valueOf(actionArg);
                }
                String result = asyncProfiler.dumpFlat(maxMethods);
                process.write(result);
            } else if (ProfilerAction.dumpTraces.equals(profilerAction)) {
                int maxTraces = 0;
                if (actionArg != null) {
                    maxTraces = Integer.valueOf(actionArg);
                }
                String result = asyncProfiler.dumpTraces(maxTraces);
                process.write(result);
            } else if (ProfilerAction.getSamples.equals(profilerAction)) {
                String result = "" + asyncProfiler.getSamples() + "\n";
                process.write(result);
            }
        } catch (Throwable e) {
            process.write(e.getMessage()).write("\n");
            logger.error("arthas", "AsyncProfiler error", e);
            status = 1;
        } finally {
            process.end(status);
        }
    }

    private List<String> events() {
        List<String> result = new ArrayList<String>();

        String execute;
        try {
            /**
             * <pre>
               Basic events:
                  cpu
                  alloc
                  lock
                  wall
                  itimer
             * </pre>
             */
            execute = this.profilerInstance().execute("list");
        } catch (Throwable e) {
            // ignore
            return result;
        }
        String lines[] = execute.split("\\r?\\n");

        if (lines != null) {
            for (String line : lines) {
                if (line.startsWith(" ")) {
                    result.add(line.trim());
                }
            }
        }
        return result;
    }

    @Override
    public void complete(Completion completion) {
        List<CliToken> tokens = completion.lineTokens();
        String token = tokens.get(tokens.size() - 1).value();

        if (tokens.size() >= 2) {
            CliToken cliToken_1 = tokens.get(tokens.size() - 1);
            CliToken cliToken_2 = tokens.get(tokens.size() - 2);
            if (cliToken_1.isBlank()) {
                String token_2 = cliToken_2.value();
                if (token_2.equals("-e") || token_2.equals("--event")) {
                    CompletionUtils.complete(completion, events());
                    return;
                } else if (token_2.equals("-f") || token_2.equals("--format")) {
                    CompletionUtils.complete(completion, Arrays.asList("svg", "html", "jfr"));
                    return;
                }
            }
        }

        if (token.startsWith("-")) {
            super.complete(completion);
            return;
        }

        Set<String> values = new HashSet<String>();
        for (ProfilerAction action : ProfilerAction.values()) {
            values.add(action.toString());
        }
        CompletionUtils.complete(completion, values);
    }

}