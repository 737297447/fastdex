package com.dx168.fastdex.build.util

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Project
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Created by tong on 17/3/14.
 */
public class FastdexUtils {
    public static final String getSdkDirectory(Project project) {
        String sdkDirectory = project.android.getSdkDirectory()
        if (sdkDirectory.contains("\\")) {
            sdkDirectory = sdkDirectory.replace("\\", "/");
        }
        return sdkDirectory
    }
    public static final String getDxCmdPath(Project project) {
        File dx = new File(FastdexUtils.getSdkDirectory(project),"build-tools${File.separator}${project.android.getBuildToolsVersion()}${File.separator}dx")
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            return "${dx.absolutePath}.bat"
        }
        return dx.getAbsolutePath()
    }

    /**
     * 获取fastdex的build目录
     * @param project
     * @return
     */
    public static final File getBuildDir(Project project) {
        File file = new File(project.getBuildDir(),Constant.FASTDEX_BUILD_DIR);
        return file;
    }

    /**
     * 获取fastdex指定variantName的build目录
     * @param project
     * @return
     */
    public static final File getBuildDir(Project project,String variantName) {
        File file = new File(getBuildDir(project),variantName);
        return file;
    }

    /**
     * 获取fastdex指定variantName的dex缓存目录
     * @param project
     * @return
     */
    public static final File getDexCacheDir(Project project,String variantName) {
        File file = new File(getBuildDir(project,variantName),Constant.FASTDEX_DEX_CACHE_DIR);
        return file;
    }

    /**
     * 把第一个字母变成小写
     * @param str
     * @return
     */
    public static final String firstCharToLowerCase(String str) {
        if (str == null || str.length() == 0) {
            return ""
        }
        return str.substring(0,1).toLowerCase() + str.substring(1)
    }

    /**
     * 是否存在dex缓存
     * @param project
     * @param variantName
     * @return
     */
    public static boolean hasDexCache(Project project, String variantName) {
        File cacheDexDir = getDexCacheDir(project,variantName)
        if (!FileUtils.dirExists(cacheDexDir.getAbsolutePath())) {
            return false;
        }

        //check dex
        boolean result = false
        for (File file : cacheDexDir.listFiles()) {
            if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                result = true
                break
            }
        }
        //check R.txt
        return result
    }

    /**
     * 清空所有缓存
     * @param project
     * @param variantName
     * @return
     */
    public static boolean cleanCache(Project project,String variantName) {
        File dir = getBuildDir(project,variantName)
        project.logger.error("==fastdex clean dir: ${dir}")
        return FileUtils.deleteDir(dir)
    }

    /**
     * 清空指定variantName缓存
     * @param project
     * @param variantName
     * @return
     */
    public static boolean cleanAllCache(Project project) {
        File dir = getBuildDir(project)
        project.logger.error("==fastdex clean dir: ${dir}")
        return FileUtils.deleteDir(dir)
    }

    /**
     * 获取资源映射文件
     * @param project
     * @param variantName
     * @return
     */
    public static File getCachedResourceMappingFile(Project project,String variantName) {
        File resourceMappingFile = new File(getBuildDir(project,variantName),Constant.R_TXT)
        return resourceMappingFile
    }

    /**
     * 获取全量打包时的依赖列表
     * @param project
     * @param variantName
     * @return
     */
    public static File getCachedDependListFile(Project project,String variantName) {
        File cachedDependListFile = new File(getBuildDir(project,variantName),Constant.DEPENDENCIES_MAPPING_FILENAME)
        return cachedDependListFile
    }

    /**
     * 获取全量打包时的包括所有代码的jar包
     * @param project
     * @param variantName
     * @return
     */
    public static File getInjectedJarFile(Project project,String variantName) {
        File injectedJarFile = new File(getBuildDir(project,variantName),Constant.INJECTED_JAR_FILENAME)
        return injectedJarFile
    }

    /**
     *扫描所有的项目代码(sourceSet、app/build/generated)
     */
    public static Set<String> getNeedInjectClassPatterns(Project project,Object applicationVariant) {
        /**
         source dir
         ├── com
         │   └── dx168
         │       └── fastdex
         │           └── sample
         │               ├── Application.class
         │               ├── BuildConfig.class
         │               └── MainActivity.class
         └── rx
         ├── Observable.class
         └── Scheduler.class

         result =>
         com.dx168.fastdex.sample.Application
         com.dx168.fastdex.sample.BuildConfig
         com.dx168.fastdex.sample.MainActivity
         rx.Observable
         rx.Scheduler
         */
        Set<String> result = new HashSet<>();
        List<String> srcLists = new ArrayList<>()
        for (String srcDir : project.android.sourceSets.main.java.srcDirs) {
            srcLists.add(srcDir);
        }

        //app/build/generated/source/buildConfig/${variantStr}
        File buildConfigDir = applicationVariant.getVariantData().getScope().getBuildConfigSourceOutputDir()
        if (FileUtils.dirExists(buildConfigDir.getAbsolutePath())) {
            srcLists.add(buildConfigDir.getAbsolutePath())
        }

        //处理butterknife的输出路径 app/build/generated/source/apt/${variantStr}
        File aptDir = new File(new File(buildConfigDir.getParentFile().getParentFile(),"apt"),buildConfigDir.getName())
        if (FileUtils.dirExists(aptDir.getAbsolutePath())) {
            srcLists.add(aptDir.getAbsolutePath())
        }

        for (String srcDir : srcLists) {
            project.logger.error("==fastdex sourceSet: " + srcDir)

            Path srcDirPath = new File(srcDir).toPath()
            Files.walkFileTree(srcDirPath,new SimpleFileVisitor<Path>(){
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.toFile().getName().endsWith(Constant.JAVA_SUFFIX)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relativePath = srcDirPath.relativize(file)
                    String className = relativePath.toString()

                    //防止windows路径出问题
                    if (className.contains("\\")) {
                        className = className.replace("\\", "/");
                    }
                    //  com/dx168/fastdex/sample/MainActivity.java => com/dx168/fastdex/sample/MainActivity
                    className = className.substring(0,className.length() - Constant.JAVA_SUFFIX.length())
                    //  com/dx168/fastdex/sample/MainActivity => com/dx168/fastdex/sample/MainActivity.class
                    result.add("${className}${Constant.CLASS_SUFFIX}")

                    //  com/dx168/fastdex/sample/MainActivity => com/dx168/fastdex/sample/MainActivity$*.class
                    result.add("${className}\$\\S{0,}${Constant.CLASS_SUFFIX}")
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return result
    }

    /**
     * 补丁打包时扫描那些java文件发生了变化
     * @param project
     * @param variantName
     * @param manifestPath
     * @return
     */
    public static Set<String> getChangedClassPatterns(Project project,String variantName,String manifestPath) {
        String[] srcDirs = project.android.sourceSets.main.java.srcDirs
        File snapshootDir = new File(getBuildDir(project,variantName),Constant.FASTDEX_SNAPSHOOT_DIR)
        Set<String> changedJavaClassNames = new HashSet<>()
        for (String srcDir : srcDirs) {
            File newDir = new File(srcDir)
            File oldDir = new File(snapshootDir,fixSourceSetDir(srcDir))

            Set<JavaDirDiff.DiffInfo> set = JavaDirDiff.diff(newDir,oldDir,true,project.logger)

            for (JavaDirDiff.DiffInfo diff : set) {
                //假如MainActivity发生变化，生成的class
                //包括MainActivity.class  MainActivity$1.class MainActivity$2.class ...
                //如果依赖的有butterknife,还会动态生成MainActivity$$ViewBinder.class，所以尽量别使用这玩意，打包会很慢的

                String className = diff.relativePath
                //className = com/dx168/fastdex/sample/MainActivity.java || com\\dx168\\fastdex\\sample\\MainActivity.java
                //防止windows路径出问题
                if (className.contains("\\")) {
                    className = className.replace("\\", "/");
                }

                className = className.substring(0,className.length() - Constant.JAVA_SUFFIX.length())
                changedJavaClassNames.add("${className}${Constant.CLASS_SUFFIX}")
                changedJavaClassNames.add("${className}\\\$\\S{0,}${Constant.CLASS_SUFFIX}")}
        }
        changedJavaClassNames.add(GradleUtils.getBuildConfigRelativePath(manifestPath))
        return changedJavaClassNames
    }

    public static String fixSourceSetDir(String srcDir) {
        if (srcDir == null || srcDir.length() == 0) {
            return srcDir
        }
//        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
//            return MessageDigest.getInstance("MD5").digest(srcDir.bytes).encodeHex().toString()
//        }
//        return srcDir
        return MessageDigest.getInstance("MD5").digest(srcDir.bytes).encodeHex().toString()
    }
}
