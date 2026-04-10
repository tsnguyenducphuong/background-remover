import { registerWebModule, NativeModule } from 'expo';

import { ExpoBackgroundRemoverModuleEvents } from './ExpoBackgroundRemover.types';

class ExpoBackgroundRemoverModule extends NativeModule<ExpoBackgroundRemoverModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ExpoBackgroundRemoverModule, 'ExpoBackgroundRemoverModule');
