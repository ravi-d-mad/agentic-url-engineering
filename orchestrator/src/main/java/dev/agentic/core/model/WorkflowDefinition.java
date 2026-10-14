package dev.agentic.core.model;

import java.util.*;

/** Validated, immutable DAG of nodes. Rejects duplicates, dangling references and cycles at construction time. */
public final class WorkflowDefinition {
    private final String name;
    private final Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
    private final Set<String> externalInputs;
    private final Map<String, String> producerOf = new HashMap<>();

    public WorkflowDefinition(String name, List<WorkflowNode> list, Set<String> externalInputs) {
        this.name = name;
        this.externalInputs = Collections.unmodifiableSet(new LinkedHashSet<>(externalInputs));
        for (WorkflowNode n : list)
            if (nodes.put(n.id(), n) != null) throw new IllegalArgumentException("duplicate node " + n.id());
        for (WorkflowNode n : list)
            for (String p : n.produces()) {
                if (externalInputs.contains(p)) throw new IllegalArgumentException(p + " is an external input");
                if (producerOf.put(p, n.id()) != null) throw new IllegalArgumentException("artifact produced twice: " + p);
            }
        for (WorkflowNode n : list) {
            for (String d : n.dependsOn())
                if (!nodes.containsKey(d)) throw new IllegalArgumentException(n.id() + " depends on unknown node " + d);
            for (String c : n.consumes())
                if (!producerOf.containsKey(c) && !externalInputs.contains(c))
                    throw new IllegalArgumentException(n.id() + " consumes unknown artifact " + c);
        }
        topologicalOrder();
    }

    public String name() { return name; }
    public Collection<WorkflowNode> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public WorkflowNode node(String id) {
        WorkflowNode n = nodes.get(id);
        if (n == null) throw new NoSuchElementException("unknown node " + id);
        return n;
    }
    public Set<String> externalInputs() { return externalInputs; }
    public boolean knowsArtifact(String a) { return externalInputs.contains(a) || producerOf.containsKey(a); }

    public Set<String> dependenciesOf(String id) {
        WorkflowNode n = node(id);
        Set<String> deps = new LinkedHashSet<>(n.dependsOn());
        for (String c : n.consumes()) { String p = producerOf.get(c); if (p != null) deps.add(p); }
        return deps;
    }

    public List<WorkflowNode> consumersOf(String artifact) {
        return nodes.values().stream().filter(n -> n.consumes().contains(artifact)).toList();
    }

    /** Kahn's algorithm; throws on cycles. */
    public List<String> topologicalOrder() {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> indeg.put(id, dependenciesOf(id).size()));
        Deque<String> q = new ArrayDeque<>();
        indeg.forEach((k, v) -> { if (v == 0) q.add(k); });
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String id = q.poll();
            order.add(id);
            for (String other : nodes.keySet())
                if (dependenciesOf(other).contains(id) && indeg.merge(other, -1, Integer::sum) == 0) q.add(other);
        }
        if (order.size() != nodes.size()) throw new IllegalArgumentException("workflow '" + name + "' contains a cycle");
        return order;
    }

    public String toMermaid() {
        StringBuilder sb = new StringBuilder("graph LR\n");
        for (WorkflowNode n : nodes.values()) {
            sb.append("  ").append(id(n.id())).append("[\"").append(n.id()).append("<br/>").append(n.autonomy()).append("\"]\n");
            for (String d : dependenciesOf(n.id())) sb.append("  ").append(id(d)).append(" --> ").append(id(n.id())).append('\n');
        }
        return sb.toString();
    }

    private static String id(String s) { return s.replaceAll("[^A-Za-z0-9]", "_"); }
}
