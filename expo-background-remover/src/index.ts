
import { BackgroundRemoverError } from './ExpoBackgroundRemover.types';
import ExpoBackgroundRemoverModule from './ExpoBackgroundRemoverModule';
 

/**
 * Removes the background from an image using on-device ML.
 * * @param imageUri - The local file URI of the source image.
 * @returns A promise that resolves to the local file URI of the transparent PNG.
 * @throws Will throw an error if the image cannot be loaded or no subjects are found.
 */
export async function removeBackgroundAsync(imageUri: string): Promise<string> {
  if (!imageUri || typeof imageUri !== 'string') {
    throw new BackgroundRemoverError('A valid image URI string is required.');
  }

  try {
    const resultUri = await ExpoBackgroundRemoverModule.removeBackgroundAsync(imageUri);
    return resultUri;
  } catch (error: any) {
    // Catch native exceptions (e.g., NoSubjectDetectedException from Swift) 
    // and format them nicely for the JS thread.
    throw new BackgroundRemoverError(error?.message || 'Failed to process image background.');
  }
}



// Reexport the native module. On web, it will be resolved to ExpoBackgroundRemoverModule.web.ts
// and on native platforms to ExpoBackgroundRemoverModule.ts
export { default } from './ExpoBackgroundRemoverModule';
export { default as ExpoBackgroundRemoverView } from './ExpoBackgroundRemoverView';
export * from  './ExpoBackgroundRemover.types';


