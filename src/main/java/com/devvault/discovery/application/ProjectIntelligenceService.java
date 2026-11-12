package com.devvault.discovery.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.devvault.discovery.application.dto.ProjectIntelligenceResponse;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.ArchitecturePattern;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Configuration;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Database;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Dependency;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Edge;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Evidence;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Module;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Node;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.ProjectMap;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.ServiceComponent;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Technology;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse.Tool;
import com.devvault.discovery.domain.Project;
import com.devvault.discovery.domain.ProjectProfile;

@Service
public class ProjectIntelligenceService {

    private static final int MAX_ARCHITECTURE_NODES = 150;

    public ProjectIntelligenceResponse describe(Project project, ProjectProfile profile) {
        Map<String, Object> markers = profile == null || profile.getRawMarkers() == null
                ? Map.of()
                : profile.getRawMarkers();
        List<Technology> stack = readStack(markers);
        if (stack.isEmpty()) {
            stack = legacyStack(project, markers);
        }
        List<Module> modules = readModules(markers);
        List<Dependency> dependencies = readDependencies(markers);
        List<Database> databases = readDatabases(markers);
        List<Tool> tools = readTools(markers);
        List<Configuration> configurations = readConfigurations(markers);
        List<ServiceComponent> services = readServices(markers);
        stack = addArchitectureTechnologies(stack, databases, tools);

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        UUID projectId = project.getId();
        String rootId = "project-" + projectId;
        nodes.add(new Node(rootId, project.getName(), "PROJECT", "PROJECT", null, List.of()));
        for (Technology technology : stack) {
            if (!addNode(nodes, edges, rootId, new Node(
                    technology.id(), technology.name(), "TECHNOLOGY", technology.category(),
                    technology.version(), technology.evidence()), "uses", technology.confidence(),
                    technology.evidence())) {
                break;
            }
        }
        for (Module module : modules) {
            if (!addNode(nodes, edges, rootId, new Node(module.id(), module.name(), "MODULE", module.type(), null,
                    List.of(module.evidence())), "contains", "DIRECT", List.of(module.evidence()))) {
                break;
            }
        }
        for (Database database : databases) {
            String relationship = switch (database.status()) {
                case "CONFIGURED" -> "configured-for";
                case "DEPENDENCY" -> "driver-declared";
                default -> "declared-service";
            };
            if (!addNode(nodes, edges, rootId,
                    new Node(database.id(), database.name(), "DATABASE", "DATABASE", null,
                            List.of(database.evidence())),
                    relationship, "DIRECT", List.of(database.evidence()))) {
                break;
            }
        }
        for (Tool tool : tools) {
            if (!addNode(nodes, edges, rootId,
                    new Node(tool.id(), tool.name(), "TOOL", "TOOLS", tool.version(), List.of(tool.evidence())),
                    "built-or-managed-by", "DIRECT", List.of(tool.evidence()))) {
                break;
            }
        }
        for (ServiceComponent service : services) {
            if (!addNode(nodes, edges, rootId,
                    new Node(service.id(), service.name(), "SERVICE", service.type(), null,
                            List.of(service.evidence())),
                    "declared-in", "DIRECT", List.of(service.evidence()))) {
                break;
            }
        }
        for (Configuration configuration : configurations) {
            Evidence evidence = new Evidence(configuration.file(), "purpose", configuration.purpose());
            if (!addNode(nodes, edges, rootId,
                    new Node(configuration.id(), configuration.file(), "CONFIGURATION",
                            configuration.purpose(), null, List.of(evidence)),
                    "configured-by", "DIRECT", List.of(evidence))) {
                break;
            }
        }
        for (Dependency dependency : dependencies) {
            if ("TRANSITIVE".equals(dependency.scope())) {
                continue;
            }
            if (!addNode(nodes, edges, rootId,
                    new Node(dependency.id(), dependency.name(), "DEPENDENCY", dependency.scope(),
                            dependency.version(), List.of(dependency.evidence())),
                    "declares", "DIRECT", List.of(dependency.evidence()))) {
                break;
            }
        }

        return new ProjectIntelligenceResponse(
                projectId,
                profile == null ? null : profile.getDetectedAt(),
                List.copyOf(stack),
                modules,
                dependencies,
                databases,
                tools,
                configurations,
                services,
                new ProjectMap(List.copyOf(nodes), connectComponents(nodes, edges, modules, services)),
                classifyArchitecture(modules),
                readEnvironmentVariables(markers.get("environmentVariables")),
                readStringList(markers.get("warnings")));
    }

    private Map<String, List<String>> readEnvironmentVariables(Object raw) {
        Map<String, List<String>> variables = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> entries) {
            entries.forEach((key, value) -> {
                if (key instanceof String name) {
                    variables.put(name, readStringList(value));
                }
            });
        }
        return variables;
    }

    private List<Edge> connectComponents(
            List<Node> nodes, List<Edge> edges, List<Module> modules, List<ServiceComponent> services) {
        Map<String, Node> nodesById = new LinkedHashMap<>();
        nodes.forEach(node -> nodesById.put(node.id(), node));
        Map<String, Module> modulesById = new LinkedHashMap<>();
        modules.stream().filter(module -> nodesById.containsKey(module.id()))
                .forEach(module -> modulesById.put(module.id(), module));
        List<Module> owners = modulesById.values().stream()
                .sorted(Comparator.comparingInt((Module module) -> module.path().length()).reversed())
                .toList();

        List<Edge> connected = new ArrayList<>(edges.stream().map(edge -> {
            Node target = nodesById.get(edge.target());
            String path = switch (target.type()) {
                case "MODULE" -> modulesById.get(target.id()).path();
                case "DEPENDENCY", "DATABASE", "TOOL", "CONFIGURATION", "SERVICE" ->
                    edge.evidence().get(0).file();
                default -> "";
            };
            return owners.stream().filter(module -> path != null && path.startsWith(module.path() + "/"))
                    .findFirst()
                    .map(owner -> new Edge("edge-" + owner.id() + "-" + edge.target(),
                            owner.id(), edge.target(), edge.relationship(), edge.confidence(), edge.evidence()))
                    .orElse(edge);
        }).toList());

        Map<String, Map<String, ServiceComponent>> servicesByFile = new LinkedHashMap<>();
        services.stream().filter(service -> nodesById.containsKey(service.id())).forEach(service ->
                servicesByFile.computeIfAbsent(service.evidence().file(), file -> new LinkedHashMap<>())
                        .put(service.name(), service));
        servicesByFile.forEach((file, byName) -> byName.values().forEach(service -> {
            for (String dependency : service.dependsOn()) {
                ServiceComponent target = byName.get(dependency);
                if (target != null && !target.id().equals(service.id())) {
                    addEdge(connected, service.id(), target.id(), "depends-on", "DIRECT",
                            List.of(new Evidence(file, "services." + service.name() + ".depends_on", dependency)));
                }
            }
        }));
        return List.copyOf(connected);
    }

    private List<ArchitecturePattern> classifyArchitecture(List<Module> modules) {
        Map<String, Map<String, Evidence>> areasByParent = new LinkedHashMap<>();
        List<Evidence> buildModules = new ArrayList<>();
        for (Module module : modules) {
            if (!"SOURCE_AREA".equals(module.type())) {
                if (Set.of("MAVEN_MODULE", "GRADLE_MODULE", "NPM_WORKSPACE", "BUILD_MODULE")
                        .contains(module.type())) {
                    buildModules.add(module.evidence());
                }
                continue;
            }
            int separator = module.path().lastIndexOf('/');
            String parent = separator < 0 ? "." : module.path().substring(0, separator);
            String area = module.name().toLowerCase(Locale.ROOT);
            area = switch (area) {
                case "controller" -> "controllers";
                case "service" -> "services";
                case "repository" -> "repositories";
                case "infra" -> "infrastructure";
                case "use-cases" -> "usecases";
                default -> area;
            };
            areasByParent.computeIfAbsent(parent, ignored -> new LinkedHashMap<>()).put(area, module.evidence());
        }

        List<ArchitecturePattern> patterns = new ArrayList<>();
        if (buildModules.size() >= 2) {
            patterns.add(new ArchitecturePattern("MULTI_MODULE", ".", "DIRECT", List.copyOf(buildModules)));
        }
        areasByParent.forEach((scope, areas) -> {
            if (areas.keySet().containsAll(List.of("ports", "adapters"))) {
                patterns.add(pattern("HEXAGONAL", scope, areas, "ports", "adapters"));
            } else if (areas.keySet().containsAll(List.of("entities", "usecases", "adapters"))) {
                patterns.add(pattern("CLEAN", scope, areas, "entities", "usecases", "adapters"));
            } else if (areas.keySet().containsAll(List.of("models", "views", "controllers"))) {
                patterns.add(pattern("MVC", scope, areas, "models", "views", "controllers"));
            } else if (areas.keySet().containsAll(List.of("domain", "application", "infrastructure"))) {
                patterns.add(pattern("LAYERED", scope, areas, "domain", "application", "infrastructure"));
            } else if (areas.keySet().containsAll(List.of("controllers", "services", "repositories"))) {
                patterns.add(pattern("LAYERED", scope, areas, "controllers", "services", "repositories"));
            }
        });
        return List.copyOf(patterns);
    }

    private ArchitecturePattern pattern(String type, String scope, Map<String, Evidence> areas, String... names) {
        return new ArchitecturePattern(type, scope, "INFERRED", List.of(names).stream().map(areas::get).toList());
    }

    private void addEdge(List<Edge> edges, String source, String target, String relationship,
            String confidence, List<Evidence> evidence) {
        if (evidence.isEmpty()) {
            return;
        }
        edges.add(new Edge(
                "edge-" + source + "-" + target,
                source,
                target,
                relationship,
                confidence,
                evidence));
    }

    private boolean addNode(List<Node> nodes, List<Edge> edges, String rootId, Node node,
            String relationship, String confidence, List<Evidence> evidence) {
        if (nodes.size() >= MAX_ARCHITECTURE_NODES) {
            return false;
        }
        nodes.add(node);
        addEdge(edges, rootId, node.id(), relationship, confidence, evidence);
        return true;
    }

    private List<Technology> readStack(Map<String, Object> markers) {
        Object rawStack = markers.get("stackFindings");
        if (!(rawStack instanceof List<?> findings)) {
            return List.of();
        }

        List<Technology> stack = new ArrayList<>();
        for (Object rawFinding : findings) {
            if (!(rawFinding instanceof Map<?, ?> finding)) {
                continue;
            }
            String name = stringValue(finding.get("name"));
            String category = stringValue(finding.get("category"));
            if (name == null || category == null) {
                continue;
            }
            List<Evidence> evidence = readEvidence(finding.get("evidence"));
            if (evidence.isEmpty()) {
                continue;
            }
            stack.add(new Technology(
                    technologyId(name, category),
                    name,
                    category,
                    stringValue(finding.get("version")),
                    stringValue(finding.get("confidence")) == null ? "DIRECT" : stringValue(finding.get("confidence")),
                    Boolean.TRUE.equals(finding.get("primary")),
                    evidence));
        }
        Map<String, Technology> unique = new LinkedHashMap<>();
        for (Technology technology : stack) {
            unique.merge(technology.id(), technology, this::mergeEvidence);
        }
        return List.copyOf(unique.values());
    }

    private List<Technology> addArchitectureTechnologies(
            List<Technology> stack, List<Database> databases, List<Tool> tools) {
        Map<String, Technology> unique = new LinkedHashMap<>();
        for (Technology technology : stack) {
            unique.put(technology.id(), technology);
        }
        for (Database database : databases) {
            String category = "DATABASE";
            unique.merge(technologyId(database.name(), category),
                    new Technology(technologyId(database.name(), category), database.name(), category, null,
                            "DIRECT", true, List.of(database.evidence())),
                    this::mergeEvidence);
        }
        for (Tool tool : tools) {
            String category = "TOOLS";
            unique.merge(technologyId(tool.name(), category),
                    new Technology(technologyId(tool.name(), category), tool.name(), category, tool.version(),
                            "DIRECT", false, List.of(tool.evidence())),
                    this::mergeEvidence);
        }
        return List.copyOf(unique.values());
    }

    private List<Module> readModules(Map<String, Object> markers) {
        List<Module> values = new ArrayList<>();
        for (Map<?, ?> value : readMapList(markers.get("architectureModules"))) {
            String path = stringValue(value.get("path"));
            String name = stringValue(value.get("name"));
            String type = stringValue(value.get("type"));
            String file = stringValue(value.get("file"));
            if (path == null || name == null || type == null || file == null) {
                continue;
            }
            values.add(new Module(
                    "module-" + idPart(path),
                    name,
                    path,
                    type,
                    new Evidence(file, stringValue(value.get("property")), path)));
        }
        return uniqueById(values, Module::id);
    }

    private List<Dependency> readDependencies(Map<String, Object> markers) {
        List<Dependency> values = new ArrayList<>();
        for (Map<?, ?> value : readMapList(markers.get("dependencies"))) {
            String name = stringValue(value.get("name"));
            String scope = stringValue(value.get("scope"));
            String manager = stringValue(value.get("manager"));
            String file = stringValue(value.get("file"));
            if (name == null || scope == null || manager == null || file == null) {
                continue;
            }
            values.add(new Dependency(
                    "dependency-" + idPart(manager + "-" + name + "-" + scope),
                    name,
                    stringValue(value.get("version")),
                    scope,
                    manager,
                    new Evidence(file, stringValue(value.get("property")), stringValue(value.get("version")))));
        }
        return uniqueById(values, Dependency::id);
    }

    private List<Database> readDatabases(Map<String, Object> markers) {
        List<Database> values = new ArrayList<>();
        for (Map<?, ?> value : readMapList(markers.get("databases"))) {
            String name = stringValue(value.get("name"));
            String status = stringValue(value.get("status"));
            String file = stringValue(value.get("file"));
            if (name == null || status == null || file == null) {
                continue;
            }
            values.add(new Database(
                    "database-" + idPart(name + "-" + status),
                    name,
                    status,
                    new Evidence(file, stringValue(value.get("property")), status)));
        }
        return uniqueById(values, Database::id);
    }

    private List<Tool> readTools(Map<String, Object> markers) {
        List<Tool> values = new ArrayList<>();
        for (Map<?, ?> value : readMapList(markers.get("tools"))) {
            String name = stringValue(value.get("name"));
            String file = stringValue(value.get("file"));
            if (name == null || file == null) {
                continue;
            }
            String version = stringValue(value.get("version"));
            values.add(new Tool(
                    "tool-" + idPart(name + "-" + file),
                    name,
                    version,
                    new Evidence(file, stringValue(value.get("property")), version)));
        }
        return uniqueById(values, Tool::id);
    }

    private List<Configuration> readConfigurations(Map<String, Object> markers) {
        List<Configuration> values = new ArrayList<>();
        for (Map<?, ?> value : readMapList(markers.get("configurations"))) {
            String file = stringValue(value.get("file"));
            String purpose = stringValue(value.get("purpose"));
            if (file != null && purpose != null) {
                values.add(new Configuration("configuration-" + idPart(file), file, purpose));
            }
        }
        return uniqueById(values, Configuration::id);
    }

    private List<ServiceComponent> readServices(Map<String, Object> markers) {
        List<ServiceComponent> values = new ArrayList<>();
        for (Map<?, ?> value : readMapList(markers.get("services"))) {
            String name = stringValue(value.get("name"));
            String type = stringValue(value.get("type"));
            String file = stringValue(value.get("file"));
            if (name == null || type == null || file == null) {
                continue;
            }
            values.add(new ServiceComponent(
                    "service-" + idPart(file + "/" + name),
                    name,
                    type,
                    stringValue(value.get("image")),
                    new Evidence(file, "services." + name, stringValue(value.get("image"))),
                    readStringList(value.get("dependsOn"))));
        }
        return uniqueById(values, ServiceComponent::id);
    }

    private <T> List<T> uniqueById(List<T> values, java.util.function.Function<T, String> id) {
        Map<String, T> unique = new LinkedHashMap<>();
        for (T value : values) {
            unique.putIfAbsent(id.apply(value), value);
        }
        return List.copyOf(unique.values());
    }

    private List<Map<?, ?>> readMapList(Object rawValues) {
        if (!(rawValues instanceof List<?> values)) {
            return List.of();
        }
        List<Map<?, ?>> maps = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                maps.add(map);
            }
        }
        return List.copyOf(maps);
    }

    private List<Technology> legacyStack(Project project, Map<String, Object> markers) {
        List<Technology> stack = new ArrayList<>();
        String file = stringValue(markers.get("marker"));
        if (file == null) {
            return List.of();
        }
        if (project.getLanguage() != null) {
            stack.add(legacyTechnology(project.getLanguage(), "LANGUAGE", null, file, "language"));
        }
        if (project.getFramework() != null) {
            stack.add(legacyTechnology(project.getFramework(), legacyCategory(project.getFramework()),
                    project.getVersion(), file, "framework"));
        }
        for (String name : readStringList(markers.get("technologies"))) {
            stack.add(legacyTechnology(name, "TOOLS", null, file, "technologies"));
        }
        return stack;
    }

    private Technology legacyTechnology(String name, String category, String version, String file, String property) {
        List<Evidence> evidence = file == null ? List.of() : List.of(new Evidence(file, property, name));
        return new Technology(technologyId(name, category), name, category, version, "DIRECT", true, evidence);
    }

    private List<Evidence> readEvidence(Object rawEvidence) {
        if (!(rawEvidence instanceof List<?> values)) {
            return List.of();
        }
        List<Evidence> evidence = new ArrayList<>();
        for (Object rawValue : values) {
            if (rawValue instanceof Map<?, ?> value) {
                String file = stringValue(value.get("file"));
                if (file != null) {
                    evidence.add(new Evidence(
                            file,
                            stringValue(value.get("property")),
                            stringValue(value.get("value"))));
                }
            }
        }
        return List.copyOf(evidence);
    }

    private List<String> readStringList(Object rawValues) {
        if (!(rawValues instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(ProjectIntelligenceService::stringValue)
                .filter(value -> value != null && !value.isBlank()).toList();
    }

    private static String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String technologyId(String name, String category) {
        return "technology-" + category.toLowerCase() + "-" + idPart(name);
    }

    private static String idPart(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private Technology mergeEvidence(Technology existing, Technology additional) {
        List<Evidence> evidence = new ArrayList<>(existing.evidence());
        for (Evidence item : additional.evidence()) {
            if (!evidence.contains(item)) {
                evidence.add(item);
            }
        }
        return new Technology(
                existing.id(),
                existing.name(),
                existing.category(),
                existing.version() == null ? additional.version() : existing.version(),
                existing.confidence(),
                existing.primary() || additional.primary(),
                List.copyOf(evidence));
    }

    private String legacyCategory(String name) {
        return switch (name.toLowerCase()) {
            case "react", "vue", "angular" -> "FRONTEND";
            case "java", "kotlin", "javascript", "typescript", "php", "python", "html", "css", "sql" -> "LANGUAGE";
            case "maven", "gradle", "composer", "pip", "pipenv", "poetry / pyproject" -> "TOOLS";
            default -> "BACKEND";
        };
    }
}
