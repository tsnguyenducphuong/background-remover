package expo.modules.backgroundremover

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.net.URL 
import expo.modules.kotlin.Promise // Ensure this is here for the Promise type
import kotlinx.coroutines.launch // Required for .launch
 

class ExpoBackgroundRemoverModule : Module() {
  // Each module class must implement the definition function. The definition consists of components
  // that describes the module's functionality and behavior.
  // See https://docs.expo.dev/modules/module-api for more details about available components.
  override fun definition() = ModuleDefinition {
    // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
    // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
    // The module will be accessible from `requireNativeModule('ExpoBackgroundRemover')` in JavaScript.
    Name("ExpoBackgroundRemover")

    // Defines constant property on the module.
    Constant("PI") {
      Math.PI
    }

    // Defines event names that the module can send to JavaScript.
    Events("onChange")

    // Defines a JavaScript synchronous function that runs the native code on the JavaScript thread.
    Function("hello") {
      "Hello world! 👋"
    }

  

   

   AsyncFunction("removeBackgroundAsync") { imageUri: String, promise: expo.modules.kotlin.Promise ->
    val context = appContext.reactContext ?: return@AsyncFunction promise.reject(
        "NO_CONTEXT", "React Context is null", null
    )

    // Use the Expo-managed background queue to run your coroutine safely
    appContext.backgroundQueue.launch {
        val processor = BackgroundRemoverProcessor(context)
        try {
            val result = processor.processImage(imageUri)
            promise.resolve(result)
        } catch (e: Exception) {
            // Rejects the JS promise with the error message
            promise.reject("ERR_BG_REMOVER", e.message, e)
        } finally {
            processor.close()
        }
    }
    }


    // Defines a JavaScript function that always returns a Promise and whose native code
    // is by default dispatched on the different thread than the JavaScript runtime runs on.
    AsyncFunction("setValueAsync") { value: String ->
      // Send an event to JavaScript.
      sendEvent("onChange", mapOf(
        "value" to value
      ))
    }

    // Enables the module to be used as a native view. Definition components that are accepted as part of
    // the view definition: Prop, Events.
    View(ExpoBackgroundRemoverView::class) {
      // Defines a setter for the `url` prop.
      Prop("url") { view: ExpoBackgroundRemoverView, url: URL ->
        view.webView.loadUrl(url.toString())
      }
      // Defines an event that the view can send to JavaScript.
      Events("onLoad")
    }
  }
}
