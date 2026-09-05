`poster-4k.mp4` is a generated two-frame solid blue 3840×2160 H.264 clip. It contains no user media.

Regenerate with FFmpeg:

```sh
ffmpeg -f lavfi -i color=c=blue:s=3840x2160:r=1 -frames:v 2 -c:v libx264 -preset ultrafast -threads 1 -pix_fmt yuv420p -movflags +faststart poster-4k.mp4
```

`playback.mp4` is a two-second solid blue 320×240 baseline H.264 clip for playback/lease tests:

```sh
ffmpeg -f lavfi -i color=c=blue:s=320x240:r=10 -t 2 -c:v libx264 -profile:v baseline -preset ultrafast -threads 1 -pix_fmt yuv420p -movflags +faststart playback.mp4
```
