package asset.pipeline.less.compilers

import groovy.util.logging.Log4j

import org.mozilla.javascript.Context
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.springframework.core.io.ClassPathResource

import asset.pipeline.AssetFile
import asset.pipeline.AssetHelper
import asset.pipeline.CacheManager

@Log4j
class LessJSCompiler extends AbstractLessCompiler {
  public static final ThreadLocal threadLocal = new ThreadLocal();
  Scriptable globalScope
  ClassLoader classLoader

  LessJSCompiler(precompiler = false) {
    super(precompiler)
    try {
      classLoader = getClass().getClassLoader()

      def shellJsResource = new ClassPathResource('asset/pipeline/less/shell.js', classLoader)
      def envRhinoJsResource = new ClassPathResource('asset/pipeline/less/env.rhino.js', classLoader)
      def hooksJsResource = new ClassPathResource('asset/pipeline/less/hooks.js', classLoader)
      def lessJsResource = new ClassPathResource('asset/pipeline/less/less-1.7.0.js', classLoader)
      def compileJsResource = new ClassPathResource('asset/pipeline/less/compile.js', classLoader)
      
      Context cx = Context.enter()
      try {  
        cx.setOptimizationLevel(-1)
        globalScope = cx.initStandardObjects()
        this.evaluateJavascript(cx, shellJsResource)
        this.evaluateJavascript(cx, envRhinoJsResource)
        this.evaluateJavascript(cx, hooksJsResource)
        this.evaluateJavascript(cx, lessJsResource)
        this.evaluateJavascript(cx, compileJsResource)
      } finally {
        Context.exit()
      }

    } catch (Exception e) {
      throw new Exception("LESS Engine initialization failed.", e)
    }
  }

  def evaluateJavascript(context, resource) {
    def inputStream = resource.inputStream
    context.evaluateReader(globalScope, new InputStreamReader(inputStream, 'UTF-8'), resource.filename, 0, null)

  }

  @Override
  public def process (String input, AssetFile assetFile) {
    try {
      if (!this.precompilerMode) {
        threadLocal.set(assetFile);
      }
      def assetRelativePath = relativePath(assetFile.file)
      // def paths = AssetHelper.scopedDirectoryPaths(new File("grails-app/assets").getAbsolutePath())

      // paths += [assetFile.file.getParent()]
      def paths = AssetHelper.getAssetPaths()
      def relativePaths = paths.collect { [it, assetRelativePath].join(AssetHelper.DIRECTIVE_FILE_SEPARATOR) }
      // println paths
      paths = relativePaths + paths


      def pathstext = paths.collect {
        def p = it.replaceAll("\\\\", "/")
        if (p.endsWith("/")) {
          "'${p}'"
        } else {
          "'${p}/'"
        }
      }.toString()
      
      def cx = Context.enter()
      try {
        def compileScope = cx.newObject(globalScope)
        compileScope.setParentScope(globalScope)
        compileScope.put("lessSrc", compileScope, input)
  
        def result = cx.evaluateString(compileScope, "compile(lessSrc, ${pathstext})", "LESS compile command", 0, null)
        return result
      } finally {
        Context.exit()
      }
    } catch (JavaScriptException e) {
      // [type:Name, message:variable @alert-padding is undefined, filename:input, index:134.0, line:10.0, callLine:NaN, callExtract:null, stack:null, column:11.0, extract:[.alert {,   padding: @alert-padding;,   margin-bottom: @line-height-computed;]
      org.mozilla.javascript.NativeObject errorMeta = (org.mozilla.javascript.NativeObject) e.value

      def errorDetails = "LESS Engine Compiler Failed - ${assetFile.file.name}.\n"
      if (precompilerMode) {
        errorDetails += "**Did you mean to compile this file individually (check docs on exclusion)?**\n"
      }
      if (errorMeta && errorMeta.get('message')) {

        errorDetails += " -- ${errorMeta.get('message')} Near Line: ${errorMeta.line}, Column: ${errorMeta.column}\n"
      }
      if (errorMeta != null && errorMeta.get('extract') != null) {
        List extractArray = (org.mozilla.javascript.NativeArray) errorMeta.get('extract')
        errorDetails += "    --------------------------------------------\n"
        extractArray.each { error ->
          errorDetails += "    ${error}\n"
        }
        errorDetails += "    --------------------------------------------\n\n"
      }

      if (precompilerMode && !assetFile.baseFile) {
        log.error(errorDetails)
        return input
      } else {
        throw new Exception(errorDetails, e)
      }

    } catch (Exception e) {
      throw new Exception("""
        LESS Engine compilation of LESS to CSS failed.
        $e
        """)
    }
  }
  
  static void print(text) {
    log.debug text
  }

  static String resolveUri(String path, NativeArray paths) {
    def assetFile = threadLocal.get();
    log.debug "resolveUri: path=${path}"
    for (Object index : paths.getIds()) {
      def it = paths.get(index, null)
      def file = new File(it, path)
      log.trace "test exists: ${file}"
      if (file.exists()) {
        log.trace "found file: ${file}"
        if (assetFile) {
          CacheManager.addCacheDependency(assetFile.file.canonicalPath, file)
        }
        return file.toURI().toString()
      }
    }

    return null
  }
}
