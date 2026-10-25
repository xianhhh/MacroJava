package xh.xianhhh.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.file.ConfigurableFileCollection;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import xh.xianhhh.asm.ConstExprBuildProcessor;

import java.io.File;

/** Applies MacroJava's post-compile transformation to a Java project. */
public final class MacroJavaPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPlugins().apply(JavaPlugin.class);
        MacroJavaExtension extension = project.getExtensions().create("macroJava", MacroJavaExtension.class);
        org.gradle.api.file.FileCollection macroJavaArtifact = project.files(MacroJavaPlugin.class.getProtectionDomain().getCodeSource().getLocation());
        project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, macroJavaArtifact);
        project.getDependencies().add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, macroJavaArtifact);
        project.getDependencies().add("testCompileOnly", macroJavaArtifact);
        project.getDependencies().add("testAnnotationProcessor", macroJavaArtifact);

        TaskProvider<JavaCompile> compileJava = project.getTasks().named("compileJava", JavaCompile.class);
        TaskProvider<JavaCompile> compileTestJava = project.getTasks().named("compileTestJava", JavaCompile.class);
        compileJava.configure(task -> task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("classes/java/main-raw")));
        compileTestJava.configure(task -> task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("classes/java/test-raw")));
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        TaskProvider<JavaExec> mainBuild = registerTransformation(project, "macroJavaBuild", "Transform main Java classes", compileJava, extension, "main", false);
        TaskProvider<JavaExec> testBuild = registerTransformation(project, "macroJavaTestBuild", "Transform test Java classes", compileTestJava, extension, "test", false);
        ((ConfigurableFileCollection) sourceSets.getByName("main").getOutput().getClassesDirs()).setFrom(
                project.files(project.getLayout().getBuildDirectory().dir("classes/java/main-macrojava")).builtBy(mainBuild));
        ((ConfigurableFileCollection) sourceSets.getByName("test").getOutput().getClassesDirs()).setFrom(
                project.files(project.getLayout().getBuildDirectory().dir("classes/java/test-macrojava")).builtBy(testBuild));
        registerTransformation(project, "macroJavaDryRun", "Preview MacroJava changes without writing classes", compileJava, extension, "main", true);
        project.afterEvaluate(ignored -> {
            configureAnnotationProcessorCheck(compileJava.get(), extension);
            configureAnnotationProcessorCheck(compileTestJava.get(), extension);
            configureOutputDirectory(project, sourceSets.getByName("main"), mainBuild, compileJava, extension.isEnabled());
            configureOutputDirectory(project, sourceSets.getByName("test"), testBuild, compileTestJava, extension.isEnabled());
        });
        project.getTasks().named("classes").configure(task -> task.dependsOn("macroJavaBuild"));
        project.getTasks().named("testClasses").configure(task -> task.dependsOn("macroJavaTestBuild"));
    }

    private static void configureOutputDirectory(Project project, SourceSet sourceSet,
                                                 TaskProvider<JavaExec> buildTask,
                                                 TaskProvider<JavaCompile> compileTask, boolean enabled) {
        File shared = sharedOutputDirectory(sourceSet);
        if (!enabled) {
            if (shared != null) compileTask.get().getDestinationDirectory().fileValue(shared);
            ((ConfigurableFileCollection) sourceSet.getOutput().getClassesDirs())
                    .setFrom(compileTask.get().getDestinationDirectory());
            return;
        }
        if (shared != null) buildTask.configure(task -> task.dependsOn(
                project.getTasks().named(sourceSet.getProcessResourcesTaskName())));
        ((ConfigurableFileCollection) sourceSet.getOutput().getClassesDirs())
                .setFrom(project.files(transformedDirectory(project, sourceSet)).builtBy(buildTask));
    }

    private static File sharedOutputDirectory(SourceSet sourceSet) {
        File resources = sourceSet.getOutput().getResourcesDir();
        File java = sourceSet.getJava().getDestinationDirectory().getAsFile().getOrNull();
        if (resources == null || java == null) return null;
        return resources.toPath().toAbsolutePath().normalize().equals(java.toPath().toAbsolutePath().normalize())
                ? resources : null;
    }

    private static File transformedDirectory(Project project, SourceSet sourceSet) {
        File shared = sharedOutputDirectory(sourceSet);
        return shared != null ? shared : project.getLayout().getBuildDirectory()
                .dir("classes/java/" + sourceSet.getName() + "-macrojava").get().getAsFile();
    }

    private static void configureAnnotationProcessorCheck(JavaCompile compile, MacroJavaExtension extension) {
        String option = "-Axh.xianhhh.constexpr.checkLegit=" + extension.isCheckLegit();
        compile.getOptions().getCompilerArgs().removeIf(argument -> argument.startsWith("-Axh.xianhhh.constexpr.checkLegit="));
        compile.getOptions().getCompilerArgs().add(option);
        if (!extension.isCheckLegit() && !compile.getOptions().getCompilerArgs().contains("-parameters")) {
            compile.getOptions().getCompilerArgs().add("-parameters");
        }
    }

    private static TaskProvider<JavaExec> registerTransformation(Project project, String taskName, String description,
                                               TaskProvider<JavaCompile> compileTask, MacroJavaExtension extension,
                                               String sourceSet, boolean dryRun) {
        return project.getTasks().register(taskName, JavaExec.class, task -> {
            task.setGroup("build");
            task.setDescription(description);
            task.dependsOn(compileTask);
            task.onlyIf(ignored -> dryRun || extension.isEnabled());
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.maxArrayElements",
                    Integer.toString(extension.getMaxArrayElements())));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.stepsPerArrayElement",
                    Long.toString(extension.getStepsPerArrayElement())));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.maxStepLimit",
                    Long.toString(extension.getMaxStepLimit())));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.baseStepLimit",
                    Long.toString(extension.getBaseStepLimit())));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.shareTemplateArray",
                    Boolean.toString(extension.isShareTemplateArray())));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.checkLegit",
                    Boolean.toString(extension.isCheckLegit())));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.preserveReflectiveMembers",
                    Boolean.toString(extension.isPreserveReflectiveMembers())));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.mode", extension.getMode().name()));
            task.doFirst(ignored -> task.systemProperty("xh.xianhhh.constexpr.maxCallDepth",
                    Integer.toString(extension.getMaxCallDepth())));
            task.setClasspath(project.files(
                    codeSource(ConstExprBuildProcessor.class),
                    codeSource(ClassReader.class),
                    codeSource(ClassNode.class),
                    codeSource(Analyzer.class)));
            task.getMainClass().set("xh.xianhhh.asm.ConstExprBuildProcessor");
            task.getOutputs().upToDateWhen(ignored -> false);
            task.getArgumentProviders().add(() -> {
                String classes = compileTask.get().getDestinationDirectory().get().getAsFile().getAbsolutePath();
                String transformed = transformedDirectory(project,
                        project.getExtensions().getByType(SourceSetContainer.class).getByName(sourceSet))
                        .getAbsolutePath();
                String report = project.getLayout().getBuildDirectory().file("reports/macrojava/" + sourceSet
                        + (dryRun ? "-dry-run" : "") + ".tsv").get().getAsFile().getAbsolutePath();
                java.util.ArrayList<String> arguments = new java.util.ArrayList<String>();
                arguments.add(classes);
                arguments.add(transformed);
                if (dryRun) arguments.add("--dry-run");
                if (extension.isShowProgress()) arguments.add("--progress");
                arguments.add("--workers=" + extension.getWorkers());
                arguments.add("--report=" + report);
                for (File entry : compileTask.get().getClasspath().getFiles()) arguments.add(entry.getAbsolutePath());
                return arguments;
            });
        });
    }

    private static File codeSource(Class<?> type) {
        try {
            return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception error) {
            throw new IllegalStateException("Cannot locate ConstExpr runtime dependency: " + type.getName(), error);
        }
    }
}
