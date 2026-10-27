import com.sun.source.util.JavacTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Syntax-only check. NOT a Minecraft/Fabric compilation or runtime test. */
public class SyntaxCheck {
    public static void main(String[] args) throws Exception {
        Path src = Path.of("src/main/java");
        List<java.io.File> files = new ArrayList<>();
        try (var walk = Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> report = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(report, null, null)) {
            var units = manager.getJavaFileObjectsFromFiles(files);
            JavacTask task = (JavacTask) compiler.getTask(null, manager, report, List.of("-proc:none"), null, units);
            task.parse();
        }
        if (!report.getDiagnostics().isEmpty()) {
            report.getDiagnostics().forEach(System.err::println);
            System.exit(1);
        }
        System.out.println("OK: " + files.size() + " Java-Quelldateien syntaktisch eingelesen.");
        System.out.println("ACHTUNG: Keine Typpruefung/Mod-Kompilierung/Server-Test.");
    }
}
