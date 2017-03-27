package com.dx168.fastdex.build.task

import com.dx168.fastdex.build.util.Constant
import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.FileUtils
import com.dx168.fastdex.build.util.GradleUtils
import com.dx168.fastdex.build.util.JavaDirDiff
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.TaskAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 每次SourceSet下的某个java文件变化时，默认的compile${variantName}JavaWithJavac任务会扫描所有的java文件
 * 处理javax.annotation.processing.AbstractProcessor接口用来代码动态代码生成，所以项目中的java文件如果很多会造成大量的时间浪费
 *
 * 全量打包时使用默认的任务，补丁打包使用此任务以提高效率(仅编译变化的java文件不去扫描代码内容)
 *
 * https://ant.apache.org/manual/Tasks/javac.html
 *
 * Created by tong on 17/3/12.
 */
public class FastdexCustomJavacTask extends DefaultTask {
    def applicationVariant
    String variantName
    String manifestPath
    Task compileTask

    FastdexCustomJavacTask() {
        group = 'fastdex'
    }

    @TaskAction
    void compile() {
        //检查缓存的有效性
        prepareEnv()

        if (!project.fastdex.useCustomCompile) {
            project.logger.error("==fastdex useCustomCompile=false,disable customJavacTask")
            return
        }

        boolean hasValidCache = FastdexUtils.hasDexCache(project,variantName)
        if (!hasValidCache) {
            compileTask.enabled = true
            return
        }

        File patchJavaFileDir = new File(FastdexUtils.getBuildDir(project,variantName),"custom-combind")
        File patchClassesFileDir = new File(FastdexUtils.getBuildDir(project,variantName),"custom-combind-classes")
        FileUtils.deleteDir(patchJavaFileDir)
        FileUtils.ensumeDir(patchClassesFileDir)

        //compare changed class
        String[] srcDirs = project.android.sourceSets.main.java.srcDirs
        File snapshootDir = new File(FastdexUtils.getBuildDir(project,variantName),Constant.FASTDEX_SNAPSHOOT_DIR)
        Set<String> changedJavaClassNames = new HashSet<>()
        for (String srcDir : srcDirs) {
            File newDir = new File(srcDir)
            File oldDir = new File(snapshootDir,FastdexUtils.fixSourceSetDir(srcDir))

            Set<JavaDirDiff.DiffInfo> set = JavaDirDiff.diff(newDir,oldDir,false,project.logger)

            for (JavaDirDiff.DiffInfo diff : set) {
                FileUtils.copyFileUsingStream(new File(diff.absolutePath),new File(patchJavaFileDir,diff.relativePath))
                changedJavaClassNames.add(diff.relativePath)
            }
        }

        if (changedJavaClassNames.isEmpty()) {
            compileTask.enabled = true
            return
        }

        //compile java
        File androidJar = new File("${FastdexUtils.getSdkDirectory(project)}/platforms/${project.android.getCompileSdkVersion()}/android.jar")
        File classpathJar = FastdexUtils.getInjectedJarFile(project,variantName)
        project.logger.error("==fastdex androidJar: ${androidJar}")
        project.logger.error("==fastdex classpath: ${classpathJar}")

        //https://ant.apache.org/manual/Tasks/javac.html
        //最好检测下项目根目录的gradle.properties文件,是否有这个配置org.gradle.jvmargs=-Dfile.encoding=UTF-8
        project.ant.javac(
                srcdir: patchJavaFileDir,
                source: '1.7',
                target: '1.7',
                encoding: 'UTF-8',
                destdir: patchClassesFileDir,
                bootclasspath: androidJar,
                classpath: classpathJar
        )

        project.logger.error("==fastdex compile success: ${patchClassesFileDir}")
        compileTask.enabled = false

        //如果变化的class直接生成jar包，并且成功hook transformClassesWithJarMergingFor${variantName}能进一步提高打包速度
        //由于无法解决一个技术点，所以这个机制暂时没有用到，暂时使用覆盖app/build/intermediates/classes内容的方式实现
        File classesDir = applicationVariant.getVariantData().getScope().getJavaOutputDir()
        Files.walkFileTree(patchClassesFileDir.toPath(),new SimpleFileVisitor<Path>(){
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relativePath = patchClassesFileDir.toPath().relativize(file)
                File destFile = new File(classesDir,relativePath.toString())
                FileUtils.copyFileUsingStream(file.toFile(),destFile)

                project.logger.error("==fastdex apply class to ${destFile}")
                return FileVisitResult.CONTINUE
            }
        })
    }

    /*
     * 检查缓存是否过期，如果过期就删除
     * 1、查看app/build/fastdex/${variantName}/dex_cache目录下是否存在dex
     * 2、检查当前的依赖列表和全两打包时的依赖是否一致(app/build/fastdex/${variantName}/dependencies-mapping.txt)
     * 3、检查当前的依赖列表和全量打包时的依赖列表是否一致
     * 4、检查资源映射文件是否存在(app/build/fastdex/${variantName}/R.txt)
     * 5、检查全量的代码jar包是否存在(app/build/fastdex/${variantName}/injected-combined.jar)
     */
    void prepareEnv() {
        //delete expired cache
        boolean hasValidCache = FastdexUtils.hasDexCache(project,variantName)
        if (hasValidCache) {
            try {
                File cachedDependListFile = FastdexUtils.getCachedDependListFile(project,variantName)
                if (!FileUtils.isLegalFile(cachedDependListFile)) {
                    throw new CheckException("miss depend list file: ${cachedDependListFile}")
                }
                //old
                Set<String> cachedDependencies = getCachedDependList()
                //current
                Set<String> currentDependencies = GradleUtils.getCurrentDependList(project,applicationVariant)
                currentDependencies.removeAll(cachedDependencies)

                //check dependencies
                //remove
                //old    current
                //1.aar  1.aar
                //2.aar

                //add
                //old    current
                //1.aar  1.aar
                //       2.aar

                //change
                //old    current
                //1.aar  1.aar
                //2.aar  xx.aar

                //handler add and change
                if (!currentDependencies.isEmpty()) {
                    throw new CheckException("${variantName.toLowerCase()} dependencies changed")
                }

                File cachedResourceMappingFile = FastdexUtils.getCachedResourceMappingFile(project,variantName)
                if (!FileUtils.isLegalFile(cachedResourceMappingFile)) {
                    throw new CheckException("miss resource mapping file: ${cachedResourceMappingFile}")
                }

                File injectedJarFile = FastdexUtils.getInjectedJarFile(project,variantName)
                if (!FileUtils.isLegalFile(injectedJarFile)) {
                    throw new CheckException("miss injected jar file: ${injectedJarFile}")
                }
            } catch (CheckException e) {
                hasValidCache = false
                project.logger.error("==fastdex ${e.getMessage()}")
                project.logger.error("==fastdex we will remove ${variantName.toLowerCase()} cache")
            }
        }

        if (hasValidCache) {
            project.logger.error("==fastdex discover cached for ${variantName.toLowerCase()}")
        }
        else {
            FastdexUtils.cleanCache(project,variantName)
            FileUtils.ensumeDir(FastdexUtils.getBuildDir(project,variantName))
        }
    }

    /**
     * 获取缓存的依赖列表
     * @return
     * @throws FileNotFoundException
     */
    Set<String> getCachedDependList() {
        Set<String> result = new HashSet<>()
        File cachedDependListFile = FastdexUtils.getCachedDependListFile(project,variantName)
        if (FileUtils.isLegalFile(cachedDependListFile)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(cachedDependListFile)))
            String line = null
            while ((line = reader.readLine()) != null) {
                result.add(line)
            }
            reader.close()
        }
        return result
    }

    private class CheckException extends Exception {
        CheckException(String var1) {
            super(var1)
        }
    }
}
