package com.dx168.fastdex.build.transform

import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInvocation
import com.dx168.fastdex.build.util.*
import org.gradle.api.GradleException
import org.gradle.api.Project

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



//            //根据变化的java文件列表生成解压的pattern
//            Set<String> changedClassPatterns = FastdexUtils.getChangedClassPatterns(project,variantName,manifestPath)
//
//            //add all changed file to jar
//            File mergedJar = new File(FastdexUtils.getBuildDir(project,variantName),"latest-merged.jar")
//            FileUtils.deleteFile(mergedJar)
//
//            //合并所有的输入jar
//            mergedJar = GradleUtils.executeMerge(project,transformInvocation,mergedJar)
//
//            if (changedClassPatterns.isEmpty()) {
//                project.logger.error("==fastdex changedClassPatterns.size == 0")
//                return mergedJar
//            }
//
//            if (project.fastdex.debug) {
//                project.logger.error("==fastdex debug mergeJar: ${mergedJar}")
//                project.logger.error("==fastdex debug changedClassPatterns: ${changedClassPatterns}")
//            }
//
//            File patchJar = new File(FastdexUtils.getBuildDir(project,variantName),"patch-combined.jar")
//            //生成补丁jar
//            JarOperation.transformPatchJar(project,mergedJar,patchJar,changedClassPatterns)
        }
        else {
            //TODO inject dir input
            base.transform(transformInvocation)
        }
    }
}