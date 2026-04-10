import * as React from 'react';

import { ExpoBackgroundRemoverViewProps } from './ExpoBackgroundRemover.types';

export default function ExpoBackgroundRemoverView(props: ExpoBackgroundRemoverViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
