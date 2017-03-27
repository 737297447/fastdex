package com.dx168.fastdex.build.transform

import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInvocation
import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.JarOperation
import org.gradle.api.Project
import com.android.build.api.transform.Format
import com.dx168.fastdex.build.util.FileUtils

/**
 * 拦截transformClassesWithJarMergingFor${variantName}任务,
 * Created by tong on 17/27/3.
 */
class FastdexJarMergingTransform extends TransformProxy {
    Project project
    def applicationVariant
    String variantName
    String manifestPath

    FastdexJarMergingTransform(Transform base, Project project, Object variant, String manifestPath) {
        super(base)
        this.project = project
        this.applicationVariant = variant
        this.variantName = variant.name.capitalize()
        this.manifestPath = manifestPath
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, IOException, InterruptedException {
        if (FastdexUtils.hasDexCache(project,variantName)) {
            project.logger.error("==fastdex generate patch jar start")
            //根据变化的java文件列表生成解压的pattern
            Set<String> changedClassPatterns = getChangedClassPatterns()
            if (changedClassPatterns.isEmpty()) {
                base.transform(transformInvocation)
                return
            }
            File outputJar = getOutputJarFile(transformInvocation)
            Set<File> dirClasspaths = getDirClasspaths(transformInvocation)
            //生成补丁jar
            JarOperation.generatePatchJar(project,dirClasspaths,outputJar,changedClassPatterns)
        }
        else {
            //TODO inject dir input
            base.transform(transformInvocation)
        }
    }

    public Set<File> getDirClasspaths(TransformInvocation invocation) {
        Set<File> dirClasspaths = new HashSet<>();
        for (TransformInput input : invocation.getInputs()) {
            Collection<DirectoryInput> directoryInputs = input.getDirectoryInputs()
            if (directoryInputs != null) {
                for (DirectoryInput directoryInput : directoryInputs) {
                    dirClasspaths.add(directoryInput.getFile())
                }
            }
        }

        return dirClasspaths
    }

    public File getOutputJarFile(TransformInvocation invocation) {
        def outputProvider = invocation.getOutputProvider();

        // all the output will be the same since the transform type is COMBINED.
        // and format is SINGLE_JAR so output is a jar
        File jarFile = outputProvider.getContentLocation("combined", base.getOutputTypes(), base.getScopes(), Format.JAR);
        FileUtils.ensumeDir(jarFile.getParentFile());

        return jarFile
    }

    public Set<String> getChangedClassPatterns() {
        return FastdexUtils.getChangedClassPatterns(project,variantName,manifestPath)
    }
}