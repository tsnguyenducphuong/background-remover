import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoBackgroundRemoverViewProps } from './ExpoBackgroundRemover.types';

const NativeView: React.ComponentType<ExpoBackgroundRemoverViewProps> =
  requireNativeView('ExpoBackgroundRemover');

export default function ExpoBackgroundRemoverView(props: ExpoBackgroundRemoverViewProps) {
  return <NativeView {...props} />;
}
