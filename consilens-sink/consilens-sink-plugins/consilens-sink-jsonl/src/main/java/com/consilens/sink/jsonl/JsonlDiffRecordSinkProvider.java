package com.consilens.sink.jsonl;

import com.consilens.sink.api.Sink;
import com.consilens.sink.api.SinkProvider;

public class JsonlDiffRecordSinkProvider implements SinkProvider {

    @Override
    public String getFormat() {
        return "jsonl";
    }

    @Override
    public String getType() {
        return "diff-record";
    }

    @Override
    public Sink create() {
        return new JsonlDiffRecordSink();
    }
}
