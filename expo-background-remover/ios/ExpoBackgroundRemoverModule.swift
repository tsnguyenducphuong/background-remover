import ExpoModulesCore
import Vision
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

public class ExpoBackgroundRemoverModule: Module {
  private let context = CIContext()
  // Each module class must implement the definition function. The definition consists of components
  // that describes the module's functionality and behavior.
  // See https://docs.expo.dev/modules/module-api for more details about available components.
  public func definition() -> ModuleDefinition {
    // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
    // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
    // The module will be accessible from `requireNativeModule('ExpoBackgroundRemover')` in JavaScript.
    Name("ExpoBackgroundRemover")

    // Defines constant property on the module.
    Constant("PI") {
      Double.pi
    }

    // Defines event names that the module can send to JavaScript.
    Events("onChange")

    // Defines a JavaScript synchronous function that runs the native code on the JavaScript thread.
    Function("hello") {
      return "Hello world! 👋"
    }

    // Defines a JavaScript function that always returns a Promise and whose native code
    // is by default dispatched on the different thread than the JavaScript runtime runs on.
    AsyncFunction("setValueAsync") { (value: String) in
      // Send an event to JavaScript.
      self.sendEvent("onChange", [
        "value": value
      ])
    }

    AsyncFunction("removeBackgroundAsync") { (imageUri: String) async throws -> String in
      guard let url = URL(string: imageUri),
            let imageData = try? Data(contentsOf: url),
            let uiImage = UIImage(data: imageData) else {
        throw ImageLoadingException()
      }

      // 1. Thread Safety: Explicitly move to a background thread for ML/Vision
      return try await withCheckedThrowingContinuation { continuation in
        DispatchQueue.global(qos: .userInitiated).async {
          do {
            // 2. Downscale to max 2048px to prevent OOM
            let processedImage = self.downscaleImage(image: uiImage, maxDimension: 2048)
            
            // 3. API Availability Check (iOS 17.0+ for Instance Masking)
            if #available(iOS 17.0, *) {
              let resultUri = try self.performModernSegmentation(image: processedImage)
              continuation.resume(returning: resultUri)
            } else {
              // 4. Fallback for older iOS versions (using Person Segmentation)
              let resultUri = try self.performLegacySegmentation(image: processedImage)
              continuation.resume(returning: resultUri)
            }
          } catch {
            continuation.resume(throwing: error)
          }
        }
      }
    }

      // Enables the module to be used as a native view. Definition components that are accepted as part of the
    // view definition: Prop, Events.
    View(ExpoBackgroundRemoverView.self) {
      // Defines a setter for the `url` prop.
      Prop("url") { (view: ExpoBackgroundRemoverView, url: URL) in
        if view.webView.url != url {
          view.webView.load(URLRequest(url: url))
        }
      }

      Events("onLoad")
    }
  }

  // MARK: - iOS 17+ Logic
  @available(iOS 17.0, *)
  private func performModernSegmentation(image: UIImage) throws -> String {
    guard let cgImage = image.cgImage else { throw ImageProcessingException() }
    
    let request = VNGenerateForegroundInstanceMaskRequest()
    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    
    try handler.perform([request])
    
    guard let result = request.results?.first else { throw NoSubjectDetectedException() }
    
    // Generate combined mask for all subjects
    let maskBuffer = try result.generateMask(forInstances: result.allInstances)
    return try applyMaskAndSave(image: image, maskBuffer: maskBuffer)
  }

  // MARK: - iOS 15/16 Fallback
  private func performLegacySegmentation(image: UIImage) throws -> String {
    // Falls back to Person Segmentation if iOS < 17
    guard let cgImage = image.cgImage else { throw ImageProcessingException() }
    let request = VNGeneratePersonSegmentationRequest()
    request.qualityLevel = .accurate
    
    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    try handler.perform([request])
    
    guard let result = request.results?.first else { throw NoSubjectDetectedException() }
    return try applyMaskAndSave(image: image, maskBuffer: result.pixelBuffer)
  }

  // MARK: - Core Image Pipeline
  private func applyMaskAndSave(image: UIImage, maskBuffer: CVPixelBuffer) throws -> String {
    guard let ciImage = CIImage(image: image) else {
      throw ImageProcessingException()
    }
    let maskImage = CIImage(cvPixelBuffer: maskBuffer)

    // Scale mask to match image
    let scaleX = ciImage.extent.width / maskImage.extent.width
    let scaleY = ciImage.extent.height / maskImage.extent.height
    let scaledMask = maskImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

    // Valid CIBlendWithMask Setup:
    // Requires: inputImage (foreground), inputBackgroundImage (background), inputMaskImage
    guard let filter = CIFilter(name: "CIBlendWithMask") else {
     throw ImageProcessingException()
    }
    filter.inputImage = ciImage
    let transparentBG = CIImage(color: .clear).cropped(to: ciImage.extent)
    filter.backgroundImage = transparentBG // Ensures transparency where mask is 0
    filter.maskImage = scaledMask

    guard let outputImage = filter.outputImage,
          let cgImage = context.createCGImage(outputImage, from: ciImage.extent) else {
      throw ImageProcessingException()
    }

    let finalImage = UIImage(cgImage: cgImage)
    let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).png")
    
    guard let data = finalImage.pngData() else { throw ImageProcessingException() }
    try data.write(to: fileURL)
    
    return fileURL.absoluteString
  }

  private func downscaleImage(image: UIImage, maxDimension: CGFloat) -> UIImage {
    let size = image.size
    if size.width <= maxDimension && size.height <= maxDimension { return image }
    let ratio = min(maxDimension / size.width, maxDimension / size.height)
    let newSize = CGSize(width: size.width * ratio, height: size.height * ratio)
    return UIGraphicsImageRenderer(size: newSize).image { _ in
      image.draw(in: CGRect(origin: .zero, size: newSize))
    }
  }


// MARK: - Exceptions
internal class ImageLoadingException: Exception {
  override var reason: String { "Could not load image from the provided URI" }
}
internal class ImageProcessingException: Exception {
  override var reason: String { "Internal error during image masking or saving" }
}
internal class NoSubjectDetectedException: Exception {
  override var reason: String { "Vision could not detect any subjects in this image" }
}


  
}

