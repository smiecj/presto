/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution.scheduler.nodeSelection;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.execution.NodeTaskMap;
import com.facebook.presto.execution.QueryManager;
import com.facebook.presto.execution.RemoteTask;
import com.facebook.presto.execution.scheduler.BucketNodeMap;
import com.facebook.presto.execution.scheduler.InternalNodeInfo;
import com.facebook.presto.execution.scheduler.NodeAssignmentStats;
import com.facebook.presto.execution.scheduler.NodeMap;
import com.facebook.presto.execution.scheduler.ResettableRandomizedIterator;
import com.facebook.presto.execution.scheduler.SplitPlacementResult;
import com.facebook.presto.metadata.InternalNode;
import com.facebook.presto.metadata.Split;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SplitContext;
import com.facebook.presto.spi.SplitWeight;
import com.facebook.presto.spi.schedule.NodeSelectionStrategy;
import com.facebook.presto.spi.ttl.ConfidenceBasedTtlInfo;
import com.facebook.presto.spi.ttl.NodeTtl;
import com.facebook.presto.ttl.nodettlfetchermanagers.NodeTtlFetcherManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.Duration;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.execution.scheduler.NodeScheduler.calculateLowWatermark;
import static com.facebook.presto.execution.scheduler.NodeScheduler.selectNodes;
import static com.facebook.presto.execution.scheduler.NodeScheduler.toWhenHasSplitQueueSpaceFuture;
import static com.facebook.presto.spi.StandardErrorCode.NODE_SELECTION_NOT_SUPPORTED;
import static com.facebook.presto.spi.StandardErrorCode.NO_NODES_AVAILABLE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.lang.String.format;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class SimpleTtlNodeSelector
        implements NodeSelector
{
    private static final Logger log = Logger.get(SimpleTtlNodeSelector.class);
    private final NodeTtlFetcherManager nodeTtlFetcherManager;
    private final Session session;
    private final AtomicReference<Supplier<NodeMap>> nodeMap;
    private final NodeTaskMap nodeTaskMap;
    private final int minCandidates;
    private final boolean includeCoordinator;
    private final long maxSplitsWeightPerNode;
    private final long maxPendingSplitsWeightPerTask;
    private final int maxTasksPerStage;
    private final SimpleNodeSelector simpleNodeSelector;
    private final QueryManager queryManager;
    private final Duration estimatedExecutionTime;
    private final boolean fallbackToSimpleNodeSelection;

    public SimpleTtlNodeSelector(
            SimpleNodeSelector simpleNodeSelector,
            SimpleTtlNodeSelectorConfig config,
            NodeTaskMap nodeTaskMap,
            Supplier<NodeMap> nodeMap,
            int minCandidates,
            boolean includeCoordinator,
            long maxSplitsWeightPerNode,
            long maxPendingSplitsWeightPerTask,
            int maxTasksPerStage,
            NodeTtlFetcherManager ttlFetcherManager,
            QueryManager queryManager,
            Session session)
    {
        this.simpleNodeSelector = requireNonNull(simpleNodeSelector, "simpleNodeSelector is null");
        this.nodeTaskMap = requireNonNull(nodeTaskMap, "nodeTaskMap is null");
        this.nodeMap = new AtomicReference<>(requireNonNull(nodeMap, "nodeMap is null"));
        this.minCandidates = minCandidates;
        this.includeCoordinator = includeCoordinator;
        this.maxSplitsWeightPerNode = maxSplitsWeightPerNode;
        this.maxPendingSplitsWeightPerTask = maxPendingSplitsWeightPerTask;
        this.maxTasksPerStage = maxTasksPerStage;
        this.nodeTtlFetcherManager = requireNonNull(ttlFetcherManager, "ttlFetcherManager is null");
        this.queryManager = requireNonNull(queryManager, "queryManager is null");
        this.session = requireNonNull(session, "session is null");
        requireNonNull(config, "config is null");
        checkArgument(session.getResourceEstimates().getExecutionTime().isPresent() || config.getUseDefaultExecutionTimeEstimateAsFallback(), "Estimated execution time is not present");
        estimatedExecutionTime = session.getResourceEstimates().getExecutionTime().orElse(config.getDefaultExecutionTimeEstimate());
        fallbackToSimpleNodeSelection = config.getFallbackToSimpleNodeSelection();
    }

    @Override
    public void lockDownNodes()
    {
        nodeMap.set(Suppliers.ofInstance(nodeMap.get().get()));
        simpleNodeSelector.lockDownNodes();
    }

    @Override
    public List<InternalNode> getActiveNodes()
    {
        return simpleNodeSelector.getActiveNodes();
    }

    @Override
    public List<InternalNode> getAllNodes()
    {
        return simpleNodeSelector.getAllNodes();
    }

    @Override
    public InternalNode selectCurrentNode()
    {
        return simpleNodeSelector.selectCurrentNode();
    }

    @Override
    public List<InternalNode> selectRandomNodes(int limit, Set<InternalNode> excludedNodes)
    {
        Map<InternalNode, NodeTtl> nodeTtlInfo = nodeTtlFetcherManager.getAllTtls();

        Map<InternalNode, Optional<ConfidenceBasedTtlInfo>> ttlInfo = nodeTtlInfo
                .entrySet()
                .stream()
                .collect(toImmutableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getTtlInfo()
                                .stream()
                                .min(Comparator.comparing(ConfidenceBasedTtlInfo::getExpiryInstant))));

        NodeMap nodeMap = this.nodeMap.get().get();
        List<InternalNode> activeNodes = nodeMap.getActiveNodes();

        Duration estimatedExecutionTimeRemaining = getEstimatedExecutionTimeRemaining();
        List<InternalNode> eligibleNodes = filterNodesByTtl(activeNodes, excludedNodes, ttlInfo, estimatedExecutionTimeRemaining);
        List<InternalNode> selectedNodes = selectNodes(limit, new ResettableRandomizedIterator<>(eligibleNodes));

        if (selectedNodes.isEmpty()) {
            if (fallbackToSimpleNodeSelection) {
                log.warn("No nodes available with enough TTL (%s), falling back to simple node selection.", estimatedExecutionTimeRemaining);
                return simpleNodeSelector.selectRandomNodes(limit, excludedNodes);
            }

            log.warn("No nodes available with enough TTL (%s). Active nodes: %s", estimatedExecutionTimeRemaining, activeNodes);
            throw new PrestoException(NO_NODES_AVAILABLE, "No nodes available to run query");
        }

        return selectedNodes;
    }

    @Override
    public SplitPlacementResult computeAssignments(Set<Split> splits, List<RemoteTask> existingTasks)
    {
        boolean isNodeSelectionStrategyNoPreference = splits.stream().allMatch(split -> split.getNodeSelectionStrategy() == NodeSelectionStrategy.NO_PREFERENCE);
        // Current NodeSelectionStrategy support is limited to NO_PREFERENCE
        if (!isNodeSelectionStrategyNoPreference) {
            return simpleNodeSelector.computeAssignments(splits, existingTasks);
        }

        ImmutableMultimap.Builder<InternalNode, Split> assignment = ImmutableMultimap.builder();
        NodeMap nodeMap = this.nodeMap.get().get();
        NodeAssignmentStats assignmentStats = new NodeAssignmentStats(nodeTaskMap, nodeMap, existingTasks);

        List<InternalNode> eligibleNodes = getEligibleNodes(maxTasksPerStage, nodeMap, existingTasks);
        NodeSelection randomNodeSelection = new RandomNodeSelection(eligibleNodes, minCandidates);

        boolean splitWaitingForAnyNode = false;

        OptionalInt preferredNodeCount = OptionalInt.empty();
        for (Split split : splits) {
            if (split.getNodeSelectionStrategy() != NodeSelectionStrategy.NO_PREFERENCE) {
                throw new PrestoException(
                        NODE_SELECTION_NOT_SUPPORTED,
                        format("Unsupported node selection strategy for TTL scheduling: %s", split.getNodeSelectionStrategy()));
            }

            List<InternalNode> candidateNodes = randomNodeSelection.pickNodes(split);
            if (candidateNodes.isEmpty()) {
                Duration remainingTime = getEstimatedExecutionTimeRemaining();
                if (fallbackToSimpleNodeSelection) {
                    log.warn("No nodes available with enough TTL (%s) to schedule %s. Active nodes %s, falling back to simple node selection.", remainingTime, split, nodeMap.getActiveNodes());
                    return simpleNodeSelector.computeAssignments(splits, existingTasks);
                }

                log.warn("No nodes available with enough TTL (%s) to schedule %s. Active nodes %s", remainingTime, split, nodeMap.getActiveNodes());
                throw new PrestoException(NO_NODES_AVAILABLE, "No nodes available to run query");
            }

            SplitWeight splitWeight = split.getSplitWeight();
            Optional<InternalNodeInfo> chosenNodeInfo = simpleNodeSelector.chooseLeastBusyNode(
                    splitWeight,
                    candidateNodes,
                    assignmentStats::getTotalSplitsWeight,
                    preferredNodeCount,
                    maxSplitsWeightPerNode,
                    assignmentStats);
            if (!chosenNodeInfo.isPresent()) {
                chosenNodeInfo = simpleNodeSelector.chooseLeastBusyNode(
                        splitWeight, candidateNodes, assignmentStats::getQueuedSplitsWeightForStage, preferredNodeCount, maxPendingSplitsWeightPerTask, assignmentStats);
            }

            if (chosenNodeInfo.isPresent()) {
                split = new Split(
                        split.getConnectorId(),
                        split.getTransactionHandle(),
                        split.getConnectorSplit(),
                        split.getLifespan(),
                        new SplitContext(chosenNodeInfo.get().isCacheable()));

                InternalNode chosenNode = chosenNodeInfo.get().getInternalNode();
                assignment.put(chosenNode, split);
                assignmentStats.addAssignedSplit(chosenNode, splitWeight);
            }
            else {
                splitWaitingForAnyNode = true;
            }
        }

        ListenableFuture<?> blocked = splitWaitingForAnyNode ?
                toWhenHasSplitQueueSpaceFuture(existingTasks, calculateLowWatermark(maxPendingSplitsWeightPerTask)) : immediateFuture(null);

        return new SplitPlacementResult(blocked, assignment.build());
    }

    @Override
    public SplitPlacementResult computeAssignments(Set<Split> splits, List<RemoteTask> existingTasks, BucketNodeMap bucketNodeMap)
    {
        return simpleNodeSelector.computeAssignments(splits, existingTasks, bucketNodeMap);
    }

    @VisibleForTesting
    public static boolean isTtlEnough(ConfidenceBasedTtlInfo ttlInfo, Duration estimatedExecutionTime)
    {
        Instant expiryTime = ttlInfo.getExpiryInstant();
        long timeRemainingInSeconds = SECONDS.between(Instant.now(), expiryTime);
        return new Duration(Math.max(timeRemainingInSeconds, 0), TimeUnit.SECONDS).compareTo(estimatedExecutionTime) >= 0;
    }

    private Duration getEstimatedExecutionTimeRemaining()
    {
        double totalEstimatedExecutionTime = estimatedExecutionTime.getValue(TimeUnit.MILLISECONDS);
        double elapsedExecutionTime = queryManager.getQueryInfo(session.getQueryId()).getQueryStats().getExecutionTime().getValue(TimeUnit.MILLISECONDS);
        double estimatedExecutionTimeRemaining = Math.max(totalEstimatedExecutionTime - elapsedExecutionTime, 0);
        return new Duration(estimatedExecutionTimeRemaining, TimeUnit.MILLISECONDS);
    }

    private List<InternalNode> getEligibleNodes(int limit, NodeMap nodeMap, List<RemoteTask> existingTasks)
    {
        Map<InternalNode, NodeTtl> nodeTtlInfo = nodeTtlFetcherManager.getAllTtls();
        Map<InternalNode, Optional<ConfidenceBasedTtlInfo>> ttlInfo = nodeTtlInfo
                .entrySet()
                .stream()
                .collect(toImmutableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getTtlInfo()
                                .stream()
                                .min(Comparator.comparing(ConfidenceBasedTtlInfo::getExpiryInstant))));

        Duration estimatedExecutionTimeRemaining = getEstimatedExecutionTimeRemaining();
        // Of the nodes on which already have existing tasks, pick only those whose TTL is enough
        List<InternalNode> existingEligibleNodes = existingTasks.stream()
                .map(remoteTask -> nodeMap.getActiveNodesByNodeId().get(remoteTask.getNodeId()))
                // nodes may sporadically disappear from the nodeMap if the announcement is delayed
                .filter(Objects::nonNull)
                .filter(ttlInfo::containsKey)
                .filter(node -> ttlInfo.get(node).isPresent())
                .filter(node -> isTtlEnough(ttlInfo.get(node).get(), estimatedExecutionTimeRemaining))
                .collect(toList());

        int alreadySelectedNodeCount = existingEligibleNodes.size();
        List<InternalNode> activeNodes = nodeMap.getActiveNodes();
        List<InternalNode> newEligibleNodes = filterNodesByTtl(activeNodes, ImmutableSet.copyOf(existingEligibleNodes), ttlInfo, estimatedExecutionTimeRemaining);

        if (alreadySelectedNodeCount < limit && newEligibleNodes.size() > 0) {
            List<InternalNode> moreNodes = selectNodes(limit - alreadySelectedNodeCount, new ResettableRandomizedIterator<>(newEligibleNodes));
            existingEligibleNodes.addAll(moreNodes);
        }
        verify(existingEligibleNodes.stream().allMatch(Objects::nonNull), "existingNodes list must not contain any nulls");
        return existingEligibleNodes;
    }

    private List<InternalNode> filterNodesByTtl(
            List<InternalNode> nodes,
            Set<InternalNode> excludedNodes,
            Map<InternalNode, Optional<ConfidenceBasedTtlInfo>> ttlInfo,
            Duration estimatedExecutionTimeRemaining)
    {
        return nodes.stream()
                .filter(ttlInfo::containsKey)
                .filter(node -> includeCoordinator || !node.isCoordinator())
                .filter(node -> !excludedNodes.contains(node))
                .filter(node -> ttlInfo.get(node).isPresent())
                .filter(node -> isTtlEnough(ttlInfo.get(node).get(), estimatedExecutionTimeRemaining))
                .collect(toImmutableList());
    }
}
