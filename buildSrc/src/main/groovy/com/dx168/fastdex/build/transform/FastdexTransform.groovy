package com.dx168.fastdex.build.transform

import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInvocation
import com.dx168.fastdex.build.util.Constant
import com.dx168.fastdex.build.util.FastdexUtils
import com.dx168.fastdex.build.util.GradleUtils
import com.dx168.fastdex.build.util.JarOperation
import com.google.common.collect.Lists
import org.gradle.api.GradleException
import org.gradle.api.Project
import com.android.build.api.transform.Format
import com.dx168.fastdex.build.util.FileUtils
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.TransformInput


/**
 * 用于dex生成
 * 全量打包时的流程:
 * 1、合并所有的class文件生成一个jar包
 * 2、扫描所有的项目代码并且在构造方法里添加对com.dx168.fastdex.runtime.antilazyload.AntilazyLoad类的依赖
 *    这样做的目的是为了解决class verify的问题，
 *    详情请看https://mp.weixin.qq.com/s?__biz=MzI1MTA1MzM2Nw==&mid=400118620&idx=1&sn=b4fdd5055731290eef12ad0d17f39d4a
 * 3、对项目代码做快照，为了以后补丁打包时对比那些java文件发生了变化
 * 4、对当前项目的所以依赖做快照，为了以后补丁打包时对比依赖是否发生了变化，如果变化需要清除缓存
 * 5、调用真正的transform生成dex
 * 6、缓存生成的dex，并且把fastdex-runtime.dex插入到dex列表中，假如生成了两个dex，classes.dex classes2.dex 需要做一下操作
 *    fastdex-runtime.dex => classes.dex
 *    classes.dex         => classes2.dex
 *    classes2.dex        => classes3.dex
 *    然后运行期在入口Application(com.dx168.fastdex.runtime.FastdexApplication)使用MultiDex把所有的dex加载进来
 * 7、保存资源映射映射表，为了保持id的值一致，详情看
 * @see com.dx168.fastdex.build.task.FastdexResourceIdTask
 *
 * 补丁打包时的流程
 * 1、检查缓存的有效性
 * @see com.dx168.fastdex.build.task.FastdexCustomJavacTask 的prepareEnv方法说明
 * 2、扫描所有变化的java文件并编译成class
 * @see com.dx168.fastdex.build.task.FastdexCustomJavacTask
 * 3、合并所有变化的class并生成jar包
 * 4、生成补丁dex
 * 5、把所有的dex按照一定规律放在transformClassesWithMultidexlistFor${variantName}任务的输出目录
 *    fastdex-runtime.dex    => classes.dex
 *    patch.dex              => classes2.dex
 *    dex_cache.classes.dex  => classes3.dex
 *    dex_cache.classes2.dex => classes4.dex
 *    dex_cache.classesN.dex => classes(N + 2).dex
 *
 * Created by tong on 17/10/3.
 */
class FastdexTransform extends TransformProxy {
    Project project
    def applicationVariant
    String variantName
    String manifestPath

    FastdexTransform(Transform base, Project project,Object variant,String manifestPath) {
        super(base)
        this.project = project
        this.applicationVariant = variant
        this.variantName = variant.name.capitalize()
        this.manifestPath = manifestPath
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, IOException, InterruptedException {
        if (FastdexUtils.hasDexCache(project,variantName)) {
            project.logger.error("==fastdex patch transform start")
            //生成补丁jar包
            File patchJar = generatePatchJar(transformInvocation)
            project.logger.error("==fastdex patch transform generate patch jar complete")

            //拼接生成dex的命令 project.android.getSdkDirectory()
            String dxcmd = FastdexUtils.getDxCmdPath(project)

            File patchDex = new File(FastdexUtils.getBuildDir(project,variantName),"patch.dex")
            FileUtils.deleteFile(patchDex)
            //TODO 补丁的方法数也有可能超过65535个，最好加上使dx生成多个dex的参数，但是一般补丁不会那么大所以暂时不处理
            dxcmd = "${dxcmd} --dex --output=${patchDex} ${patchJar}"
            project.logger.error("==fastdex patch transform generate dex cmd \n" + dxcmd)

            long start = System.currentTimeMillis();
            //调用dx命令
            def process = dxcmd.execute()
            int status = process.waitFor()
            process.destroy()
            long end = System.currentTimeMillis();
            if (status == 0) {
                project.logger.error("==fastdex patch transform generate dex success: \n==${patchDex} use: ${end - start}ms")
                //获取dex输出路径
                File dexOutputDir = getDexOutputDir(transformInvocation)

                if (project.fastdex.debug) {
                    project.logger.error("==fastdex patch transform dex dir: ${dexOutputDir}")
                }
                //复制补丁打包的dex到输出路径
                hookPatchBuildDex(dexOutputDir,patchDex)
            }
            else {
                throw new GradleException("==fastdex generate dex fail: \n${dxcmd}")
            }
        }
        else {
            project.logger.error("==fastdex normal transform start")
            //normal build
            File combinedJar = new File(FastdexUtils.getBuildDir(project,variantName),Constant.COMBINED_JAR_FILENAME)
            combinedJar = GradleUtils.executeMerge(project,transformInvocation,combinedJar)
            if (FileUtils.isLegalFile(combinedJar)) {
                project.logger.error("==fastdex normal transform merge jar success: \n${combinedJar}")
            }
            else {
                throw new GradleException("==fastdex normal transform merge jar fail: \noutputJar: ${combinedJar}")
            }
            File injectedJar = FastdexUtils.getInjectedJarFile(project,variantName)
            //往项目代码注入解决pre-verify问题的code
            JarOperation.transformNormalJar(project,combinedJar,injectedJar,FastdexUtils.getNeedInjectClassPatterns(project,applicationVariant))

            if (!FileUtils.isLegalFile(injectedJar)) {
                throw new GradleException("==fastdex normal transform inject jar fail: \ninputJar: ${combinedJar}\noutputJar: ${injectedJar}")
            }
            FileUtils.deleteFile(combinedJar)

            //生成项目代码快照
            createSourceSetSnapshoot()
            //保存依赖列表
            keepDependenciesList()
            //调用默认转换方法
            base.transform(GradleUtils.createNewTransformInvocation(this,transformInvocation,injectedJar))

            //获取dex输出路径
            File dexOutputDir = getDexOutputDir(transformInvocation)
            //缓存dex
            cacheNormalBuildDex(dexOutputDir)
            //复制全量打包的dex到输出路径
            hookNormalBuildDex(dexOutputDir)
            //save R.txt
            copyRTxt()

            project.logger.error("==fastdex normal transform end")
        }
    }

    /**
     * 生成补丁jar包
     * @param transformInvocation
     * @return
     */
    File generatePatchJar(TransformInvocation transformInvocation) {
        def config = applicationVariant.getVariantData().getVariantConfiguration()
        boolean isMultiDexEnabled = config.isMultiDexEnabled()
        if (isMultiDexEnabled) {
            //如果开启了multidex,FastdexJarMergingTransform完成了jar merge的操作
            List<JarInput> jarInputs = Lists.newArrayList();
            for (TransformInput input : transformInvocation.getInputs()) {
                jarInputs.addAll(input.getJarInputs());
            }

            File patchJar = jarInputs.get(0).getFile()
            project.logger.error("==fastdex multiDex enabled use patch.jar: ${patchJar}")
            return patchJar
        }

        //根据变化的java文件列表生成解压的pattern
        Set<String> changedClassPatterns = FastdexUtils.getChangedClassPatterns(project,variantName,manifestPath)

        //add all changed file to jar
        File mergedJar = new File(FastdexUtils.getBuildDir(project,variantName),"latest-merged.jar")
        FileUtils.deleteFile(mergedJar)

        //合并所有的输入jar
        mergedJar = GradleUtils.executeMerge(project,transformInvocation,mergedJar)

        if (changedClassPatterns.isEmpty()) {
            project.logger.error("==fastdex changedClassPatterns.size == 0")
            return mergedJar
        }

        if (project.fastdex.debug) {
            project.logger.error("==fastdex debug mergeJar: ${mergedJar}")
            project.logger.error("==fastdex debug changedClassPatterns: ${changedClassPatterns}")
        }

        File patchJar = new File(FastdexUtils.getBuildDir(project,variantName),"patch-combined.jar")
        //生成补丁jar
        JarOperation.transformPatchJar(project,mergedJar,patchJar,changedClassPatterns)

        if (!FileUtils.isLegalFile(patchJar)) {
            throw new GradleException("==fastdex generate patchJar fail: \nclassesDir: ${classesDir}\npatchJar: ${patchJar}")
        }

        project.logger.error("==fastdex will generate dex file ${changedClassPatterns}")
        return patchJar
    }

    /**
     * 保存资源映射文件
     */
    void copyRTxt() {
        File sourceFile = new File(applicationVariant.getVariantData().getScope().getSymbolLocation(),"R.txt")
        File destFile = new File(FastdexUtils.getBuildDir(project,variantName),Constant.R_TXT)
        FileUtils.copyFileUsingStream(sourceFile,destFile)
    }

    /**
     * 生成项目代码快照
     * TODO 目前是复制了所有java文件，如果把信息都写到txt文件里，能够在IO上省一些时间
     */
    void createSourceSetSnapshoot() {
        String[] srcDirs = project.android.sourceSets.main.java.srcDirs
        File snapshootDir = new File(FastdexUtils.getBuildDir(project,variantName),Constant.FASTDEX_SNAPSHOOT_DIR)
        FileUtils.ensumeDir(snapshootDir)
        for (String srcDir : srcDirs) {
            //之前使用gradle的api复制文件，但是lastModified会发生变化造成对比出问题，所以换成自己的实现
            FileUtils.copyDir(new File(srcDir),new File(snapshootDir,FastdexUtils.fixSourceSetDir(srcDir)),Constant.JAVA_SUFFIX)
        }
    }

    /**
     * 保存全量打包时的依赖列表
     */
    void keepDependenciesList() {
        Set<String> dependenciesList = GradleUtils.getCurrentDependList(project,applicationVariant)
        StringBuilder sb = new StringBuilder()
        dependenciesList.each {
            sb.append(it)
            sb.append("\n")
        }

        File dependenciesListFile = new File(FastdexUtils.getBuildDir(project,variantName),Constant.DEPENDENCIES_MAPPING_FILENAME);
        FileUtils.write2file(sb.toString().getBytes(),dependenciesListFile)
    }

    /**
     * 获取transformClassesWithDexFor${variantName}任务的dex输出目录
     * @param transformInvocation
     * @return
     */
    File getDexOutputDir(TransformInvocation transformInvocation) {
        def outputProvider = transformInvocation.getOutputProvider();
        File outputDir = null;
        try {
            outputDir = outputProvider.getContentLocation("main", base.getOutputTypes(), base.getScopes(), Format.DIRECTORY);
        } catch (Throwable e) {
            e.printStackTrace()
        }

        return outputDir;
    }

    /**
     * 缓存全量打包时生成的dex
     * @param dexOutputDir dex输出路径
     */
    void cacheNormalBuildDex(File dexOutputDir) {
        project.logger.error("==fastdex dex output directory: " + dexOutputDir)

        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)
        File[] files = dexOutputDir.listFiles()
        files.each { file ->
            if (file.getName().endsWith(".dex")) {
                FileUtils.copyFileUsingStream(file,new File(cacheDexDir,file.getName()))
            }
        }
    }

    /**
     * 全量打包时复制dex到指定位置
     * @param dexOutputDir dex输出路径
     */
    void hookNormalBuildDex(File dexOutputDir) {
        //dexelements [fastdex-runtime.dex ${dex_cache}.listFiles]
        //runtime.dex            => classes.dex
        //dex_cache.classes.dex  => classes2.dex
        //dex_cache.classes2.dex => classes3.dex
        //dex_cache.classesN.dex => classes(N + 1).dex


        //classes.dex  => classes2.dex.tmp
        //classes2.dex => classes3.dex.tmp
        //classes2.dex => classes4.dex.tmp
        //classesN.dex => classes(N + 1).dex.tmp
        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

        String tmpSuffix = ".tmp"
        new File(dexOutputDir,"classes.dex").renameTo(new File(dexOutputDir,"classes2.dex${tmpSuffix}"))

        int point = 2
        File dexFile = new File(dexOutputDir,"classes" + point + ".dex")
        while (FileUtils.isLegalFile(dexFile)) {
            new File(dexOutputDir,"classes${point}.dex").renameTo(new File(dexOutputDir,"classes${point + 1}.dex${tmpSuffix}"))
            point++
            dexFile = new File(cacheDexDir,"classes${point}.dex")
        }

        //fastdex-runtime.dex = > classes.dex
        //copy fastdex-runtime.dex
        FileUtils.copyResourceUsingStream(Constant.RUNTIME_DEX_FILENAME,new File(dexOutputDir,"classes.dex"))

        //classes2.dex.tmp => classes2.dex.tmp
        //classes3.dex.tmp => classes3.dex.tmp
        //classesN.dex.tmp => classesN.dex.tmp
        point = 2
        dexFile = new File(dexOutputDir,"classes${point}.dex${tmpSuffix}")
        while (FileUtils.isLegalFile(dexFile)) {
            dexFile.renameTo(new File(dexOutputDir,"classes${point}.dex"))
            point++
            dexFile = new File(dexOutputDir,"classes${point}.dex${tmpSuffix}")
        }
        printLogWhenDexGenerateComplete(dexOutputDir,true)
    }

    /**
     * 补丁打包时复制dex到指定位置
     * @param dexOutputDir dex输出路径
     */
    void hookPatchBuildDex(File dexOutputDir,File patchDex) {
        //dexelements [fastdex-runtime.dex patch.dex ${dex_cache}.listFiles]
        //runtime.dex            => classes.dex
        //patch.dex              => classes2.dex
        //dex_cache.classes.dex  => classes3.dex
        //dex_cache.classes2.dex => classes4.dex
        //dex_cache.classesN.dex => classes(N + 2).dex
        project.logger.error("==fastdex patch transform hook patch dex start")

        FileUtils.cleanDir(dexOutputDir)
        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

        //copy fastdex-runtime.dex
        FileUtils.copyResourceUsingStream(Constant.RUNTIME_DEX_FILENAME,new File(dexOutputDir,"classes.dex"))
        //copy patch.dex
        FileUtils.copyFileUsingStream(patchDex,new File(dexOutputDir,"classes2.dex"))
        FileUtils.copyFileUsingStream(new File(cacheDexDir,"classes.dex"),new File(dexOutputDir,"classes3.dex"))

        int point = 2
        File dexFile = new File(cacheDexDir,"classes" + point + ".dex")
        while (FileUtils.isLegalFile(dexFile)) {
            FileUtils.copyFileUsingStream(dexFile,new File(dexOutputDir,"classes" + (point + 2) + ".dex"))
            point++
            dexFile = new File(cacheDexDir,"classes" + point + ".dex")
        }

        printLogWhenDexGenerateComplete(dexOutputDir,false)
    }

    /**
     * 当dex生成完成后打印日志
     * @param normalBuild
     */
    void printLogWhenDexGenerateComplete(File dexOutputDir,boolean normalBuild) {
        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

        //log
        StringBuilder sb = new StringBuilder()
        sb.append("cached_dex[")
        File[] dexFiles = cacheDexDir.listFiles()
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (file != dexFiles[dexFiles.length - 1]) {
                    sb.append(",")
                }
            }
        }
        sb.append("] cur-dex[")
        dexFiles = dexOutputDir.listFiles()
        int idx = 0
        for (File file : dexFiles) {
            if (file.getName().endsWith(Constant.DEX_SUFFIX)) {
                sb.append(file.getName())
                if (idx < (dexFiles.length - 1)) {
                    sb.append(",")
                }
            }
            idx ++
        }
        sb.append("]")
        if (normalBuild) {
            project.logger.error("==fastdex first build ${sb}")
        }
        else {
            project.logger.error("==fastdex patch build ${sb}")
        }
    }
}