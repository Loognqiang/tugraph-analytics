/*
 * Copyright 2023 AntGroup CO., Ltd.
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
 */

package com.antgroup.geaflow.example.graph.statical.compute.kcore;

import com.antgroup.geaflow.api.function.io.SinkFunction;
import com.antgroup.geaflow.api.graph.compute.VertexCentricCompute;
import com.antgroup.geaflow.api.graph.function.vc.VertexCentricCombineFunction;
import com.antgroup.geaflow.api.graph.function.vc.VertexCentricComputeFunction;
import com.antgroup.geaflow.api.pdata.stream.window.PWindowStream;
import com.antgroup.geaflow.api.window.impl.AllWindow;
import com.antgroup.geaflow.common.config.Configuration;
import com.antgroup.geaflow.env.Environment;
import com.antgroup.geaflow.example.config.ExampleConfigKeys;
import com.antgroup.geaflow.example.data.GraphDataSet;
import com.antgroup.geaflow.example.function.AbstractVcFunc;
import com.antgroup.geaflow.example.function.FileSink;
import com.antgroup.geaflow.example.function.FileSource;
import com.antgroup.geaflow.example.util.EnvironmentUtil;
import com.antgroup.geaflow.example.util.ExampleSinkFunctionFactory;
import com.antgroup.geaflow.example.util.PipelineResultCollect;
import com.antgroup.geaflow.example.util.ResultValidator;
import com.antgroup.geaflow.example.util.VertexEdgeParser;
import com.antgroup.geaflow.model.graph.edge.IEdge;
import com.antgroup.geaflow.model.graph.vertex.IVertex;
import com.antgroup.geaflow.pipeline.IPipelineResult;
import com.antgroup.geaflow.pipeline.Pipeline;
import com.antgroup.geaflow.pipeline.PipelineFactory;
import com.antgroup.geaflow.pipeline.task.PipelineTask;
import com.antgroup.geaflow.view.GraphViewBuilder;
import com.antgroup.geaflow.view.IViewDesc.BackendType;
import com.antgroup.geaflow.view.graph.GraphViewDesc;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KCore {

    private static final Logger LOGGER = LoggerFactory.getLogger(KCore.class);

    public static final String REFERENCE_FILE_PATH = "data/reference/kcore";

    public static final String RESULT_FILE_DIR = "./target/tmp/data/result/kcore";

    public static void main(String[] args) {
        Environment environment = EnvironmentUtil.loadEnvironment(args);
        IPipelineResult<?> result = KCore.submit(environment);
        PipelineResultCollect.get(result);
        environment.shutdown();
    }

    public static IPipelineResult<?> submit(Environment environment) {
        ResultValidator.cleanResult(RESULT_FILE_DIR);
        Configuration envConfig = environment.getEnvironmentContext().getConfig();
        envConfig.put(FileSink.OUTPUT_DIR, RESULT_FILE_DIR);

        Pipeline pipeline = PipelineFactory.buildPipeline(environment);
        pipeline.submit((PipelineTask) pipelineTaskCxt -> {
            Configuration config = pipelineTaskCxt.getConfig();
            int sourceParallelism = config.getInteger(ExampleConfigKeys.SOURCE_PARALLELISM);
            int iterationParallelism = config.getInteger(ExampleConfigKeys.ITERATOR_PARALLELISM);
            int sinkParallelism = config.getInteger(ExampleConfigKeys.SINK_PARALLELISM);
            LOGGER.info("with {} {} {}", sourceParallelism, iterationParallelism, sinkParallelism);

            FileSource<IVertex<Integer, Boolean>> vSource = new FileSource<>(
                GraphDataSet.DATASET_FILE, VertexEdgeParser::vertexParserBoolean);
            PWindowStream<IVertex<Integer, Boolean>> vertices =
                pipelineTaskCxt.buildSource(vSource, AllWindow.getInstance()).withParallelism(sourceParallelism);

            FileSource<IEdge<Integer, Boolean>> eSource = new FileSource<>(
                GraphDataSet.DATASET_FILE, VertexEdgeParser::edgeParserBoolean);
            PWindowStream<IEdge<Integer, Boolean>> edges =
                pipelineTaskCxt.buildSource(eSource, AllWindow.getInstance()).withParallelism(sourceParallelism);

            GraphViewDesc graphViewDesc = GraphViewBuilder
                .createGraphView(GraphViewBuilder.DEFAULT_GRAPH)
                .withShardNum(iterationParallelism)
                .withBackend(BackendType.Memory)
                .build();
            PWindowStream<IVertex<Integer, Boolean>> result =
                pipelineTaskCxt.buildWindowStreamGraph(vertices, edges, graphViewDesc)
                .compute(new KCoreAlgorithm(2,50))
                .compute(iterationParallelism)
                .getVertices();

            SinkFunction<String> sink = ExampleSinkFunctionFactory.getSinkFunction(config);
            result.map(v -> String.format("%s,%s", v.getId(), v.getValue()))
                .sink(sink).withParallelism(sinkParallelism);
        });

        return pipeline.execute();
    }

    public static void validateResult() throws IOException {
        ResultValidator.validateMapResult(REFERENCE_FILE_PATH, RESULT_FILE_DIR);
    }

    public static class KCoreAlgorithm extends VertexCentricCompute<Integer, Boolean, Boolean, Integer> {

        private final int core;

        public KCoreAlgorithm(int core, long iterations) {
            super(iterations);
            this.core = core;
        }

        @Override
        public VertexCentricComputeFunction<Integer, Boolean, Boolean, Integer> getComputeFunction() {
            return new KCoreVCCFunction(core);
        }

        @Override
        public VertexCentricCombineFunction<Integer> getCombineFunction() {
            return null;
        }

    }

    public static class KCoreVCCFunction extends AbstractVcFunc<Integer, Boolean, Boolean, Integer> {

        private final int core;

        public KCoreVCCFunction(int core) {
            this.core = core;
        }

        @Override
        public void compute(Integer vertexId, Iterator<Integer> messageIterator) {
            IVertex<Integer, Boolean> vertex = this.context.vertex().get();
            if (this.context.getIterationId() == 1) {
                this.context.setNewVertexValue(true);
            } else {
                if (!vertex.getValue()) {
                    return;
                }
                List<Integer> removedVertexIds = new ArrayList<>();
                while (messageIterator.hasNext()) {
                    removedVertexIds.add(messageIterator.next());
                }
                List<IEdge<Integer, Boolean>> outEdges = this.context.edges().getOutEdges();
                for (IEdge<Integer, Boolean> outEdge : outEdges) {
                    if (removedVertexIds.contains(outEdge.getTargetId())) {
                        outEdge.withValue(false);
                    }
                }
            }
            boolean vertexAvailable = vertexEdgeAvailableCheck();
            if (!vertexAvailable) {
                this.context.sendMessageToNeighbors(vertex.getId());
            }
        }

        private boolean vertexEdgeAvailableCheck() {
            List<IEdge<Integer, Boolean>> outEdges = this.context.edges().getOutEdges();
            int availableEdgeCount;
            if (outEdges != null) {
                availableEdgeCount = (int) outEdges.stream().filter(IEdge::getValue).count();
            } else {
                availableEdgeCount = 0;
            }
            if (availableEdgeCount < core) {
                this.context.setNewVertexValue(false);
                return false;
            } else {
                return true;
            }
        }

    }

}

