/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.common.messages;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import kotlin.collections.CollectionsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class GroupingMessageCollector implements MessageCollector {
    private final MessageCollector delegate;
    private final boolean treatWarningsAsErrors;
    private final boolean reportAllWarnings;

    // Note that the key in this map can be null
    private final Multimap<CompilerMessageSourceLocation, Message> groupedMessages = LinkedHashMultimap.create();

    public GroupingMessageCollector(@NotNull MessageCollector delegate, boolean treatWarningsAsErrors, boolean reportAllWarnings) {
        this.delegate = delegate;
        this.treatWarningsAsErrors = treatWarningsAsErrors;
        this.reportAllWarnings = reportAllWarnings;
    }

    @Override
    public void clear() {
        groupedMessages.clear();
    }

    @Override
    public void report(
            @NotNull CompilerMessageSeverity severity,
            @NotNull String message,
            @Nullable CompilerMessageSourceLocation location
    ) {
        if (severity == CompilerMessageSeverity.OUTPUT || CompilerMessageSeverity.VERBOSE.contains(severity)) {
            delegate.report(severity, message, location);
        } else {
            groupedMessages.put(location, new Message(severity, message, location));
        }
    }

    @Override
    public boolean hasErrors() {
        return hasExplicitErrors() || (treatWarningsAsErrors && hasWarnings());
    }

    private boolean hasExplicitErrors() {
        return groupedMessages.entries().stream().anyMatch(entry -> entry.getValue().severity.isError());
    }

    private boolean hasWarnings() {
        return groupedMessages.entries().stream().anyMatch(entry -> entry.getValue().severity.isWarning());
    }

    public void flush() {
        boolean hasExplicitErrors = hasExplicitErrors();

        if (treatWarningsAsErrors && !hasExplicitErrors && hasWarnings()) {
            report(CompilerMessageSeverity.ERROR, "warnings found and -Werror specified", null);
        }

        List<CompilerMessageSourceLocation> sortedKeys =
                CollectionsKt.sortedWith(groupedMessages.keySet(), Comparator.nullsFirst(CompilerMessageLocationComparator.INSTANCE));
        for (CompilerMessageSourceLocation location : sortedKeys) {
            for (Message message : groupedMessages.get(location)) {
                if (!hasExplicitErrors || reportAllWarnings || message.severity.isError() || message.severity == CompilerMessageSeverity.STRONG_WARNING) {
                    delegate.report(message.severity, message.message, message.location);
                }
            }
        }

        groupedMessages.clear();
    }

    private static class CompilerMessageLocationComparator implements Comparator<CompilerMessageSourceLocation> {
        public static final CompilerMessageLocationComparator INSTANCE = new CompilerMessageLocationComparator();

        // First, output all messages without any location information. Then, only those with the file path.
        // Next, all messages with the file path and the line number. Next, all messages with file path, line number and column number.
        //
        // Example of the order of compiler messages:
        //
        // error: bad classpath
        // foo.kt: error: bad file
        // foo.kt:42: error: bad line
        // foo.kt:42:43: error: bad character
        @Override
        public int compare(CompilerMessageSourceLocation o1, CompilerMessageSourceLocation o2) {
            if (o1.getColumn() == -1 && o2.getColumn() != -1) return -1;
            if (o1.getColumn() != -1 && o2.getColumn() == -1) return 1;

            if (o1.getLine() == -1 && o2.getLine() != -1) return -1;
            if (o1.getLine() != -1 && o2.getLine() == -1) return 1;

            return o1.getPath().compareTo(o2.getPath());
        }
    }

    private static class Message {
        private final CompilerMessageSeverity severity;
        private final String message;
        private final CompilerMessageSourceLocation location;

        private Message(@NotNull CompilerMessageSeverity severity, @NotNull String message, @Nullable CompilerMessageSourceLocation location) {
            this.severity = severity;
            this.message = message;
            this.location = location;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Message other = (Message) o;

            if (!Objects.equals(location, other.location)) return false;
            if (!message.equals(other.message)) return false;
            if (severity != other.severity) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = severity.hashCode();
            result = 31 * result + message.hashCode();
            result = 31 * result + (location != null ? location.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "[" + severity + "] " + message + (location != null ? " (at " + location + ")" : " (no location)");
        }
    }
}
