import { NativeModule, requireNativeModule } from 'expo';

import { ExpoBackgroundRemoverModuleEvents } from './ExpoBackgroundRemover.types';

declare class ExpoBackgroundRemoverModule extends NativeModule<ExpoBackgroundRemoverModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
  removeBackgroundAsync(imageUri: string): Promise<string> ;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoBackgroundRemoverModule>('ExpoBackgroundRemover');
