# Simple Music App

Android app that implements a small silent music player using the Android Media APIs and native C++
code.

The following are sections notes that I took while
reading https://developer.android.com/guide/topics/media.

## Music app architecture

A music player app should be separated into two parts:

- A `MediaController`: UI that presents player transport controls and displays the player's state.
    - UI code only communicates with the `MediaController`.
- A `MediaSession`: entity holding the actual player.
    - Responsible for all communication with the player.
    - The player is only called from the `MediaSession`.

![controller-session.png](docs_images/controller-session.png)

The `MediaController` and `MediaSession` communicate with each other using predefined callbacks that
correspond to standard player actions (play, pause, stop, etc.), as well as extensible custom calls
used to define special behaviors unique to your app. The `MediaController` translates transport
control actions into callbacks to the `MediaSession`. It also receives callbacks from the
`MediaSession` whenever the player state changes in order to update the UI.

![controller-session-detailed.png](docs_images/controller-session-detailed.png)

### How is state represented?

The state of a player associated to `MediaSession` is represented by two instances attached to the `MediaSession`:
- An instance of `PlaybackState`: describes the player's current operational state
  - State: Playing/Paused/Buffering/Stopped
  - Player position
  - Valid controller actions that can be handled in the current state. There are two types of actions:
    - Built-in, common actions (such as play, pause, rewind).
    - Custom actions
- An instance of `MediaMetadata`: describes the material currently playing.
  - Name of current artist, album, track
  - Duration of track
  - Album artwork

### Design/Architecture

A `MediaController` can only connect to one `MediaSession` at a time, but a `MediaSession` can
send/receive callbacks to/from one or more `MediaController`s at a time. This makes it possible for
your player to be controlled by your app's UI as well as companion devices running Wear OS and
Android Auto. A music app is expected to handle multiple simultaneous connections.

This design is implemented with a client-server architecture

- The client is an Activity for the UI.
    - Will hold a `MediaBrowser`, which in turn will hold the `MediaController`.
- The server is an Android Service for the player (so it runs in the background)
    - Implemented as `MediaBrowserService`.
    - Will hold the `MediaSession` and the player.

![client-service-architecture.png](docs_images/client-service-architecture.png)

**Question: why do we need to introduce yet another layer, the `MediaBrowser`? Doesn't the `MediaController` suffice?**

Two reasons:

1. Because we forcefully need a service (`MediaBrowserService`) to implement the client-server approach
for audio apps, we also need the `MediaBrowser`. By itself, the `MediaController` is not enough: we
need the `MediaBrowser` because it is the only entity capable of communicating with a `MediaBrowser`
service, hence we can't ditch it.
2. Follow-up on the first reason: a media `MediaController` is used for both client-server architectures (for audio apps) and single-activity architectures (for video apps). Had the `MediaController` been the only necessary component for client-server architectures, the video apps would've been doomed.

A `MediaBrowserService` provides two features:
- It makes it easy for companion devices to discover your app,  create their own `MediaController`, connect to your `MediaSession`, and control playback, without accessing your app's UI activity at all.
- It also provides an optional browsing API that lets clients query the service and build out a representation of its content hierarchy, which might represent playlists, a media library, etc..

### Note: Use Compat classes (NOTE: Where should I place this paragraph?)

The recommended implementation of media sessions and media controllers are the classes
`MediaSessionCompat` and `MediaControllerCompat`. When you use these compat classes, you can remove
all calls to `registerMediaButtonReceiver()` and any methods from `RemoteControlClient`.

### How to setup a `MediaBrowserService` with a `MediaSession`?

1. Declare the `MediaBrowserService` with an intent-filter in its manifest:

```xml
<service android:name=".MediaPlaybackService">
  <intent-filter>
    <action android:name="android.media.browse.MediaBrowserService" />
  </intent-filter>
</service>
```

The `MediaSession` will be instantiated and initialized in the `onCreate` method.

Steps:

- Set flags so that the `MediaSession` can receive callbacks from `MediaController`s and media
  buttons.
- Create and initialize instances of `PlaybackState` and `MediaMetadata` and assign them to the session (cache
  the builders for reuse)
  - In order for media buttons to work when your app is newly initialized (or stopped),
  its `PlaybackState` must contain an action matching the intent that the media button sends.
  This is why `ACTION_PLAY` is assigned to the session's `PlaybackState`
  during initialization: so that the "Play" command triggers correctly when the player has just been
  created.
- Create an instance of `MediaSession.Callback` and assign it to the session.

## Playback Resumption

Users can restart previous playback/media sessions from the music carousel (located near the Quick
Settings) without having to restart the app. When playback begins, the user interacts with the media
controls in the usual way.

<img src="docs_images/carousel.png" height="600">

In order to use this feature, you must enable Media resumption in the Developer Options settings.

After the device boots, the system looks for the five most recently used media apps, and provides
controls that can be used to restart playing from each app

The system attempts to contact your `MediaBrowserService` with a connection from SystemUI. Your app
must allow such connections, otherwise it cannot support playback resumption.

Connections from SystemUI can be identified and verified using the package
name `com.android.systemui` and signature. The SystemUI is signed with the platform signature.

In order to support playback resumption, your MediaBrowserService must implement these behaviors:

onGetRoot() must return a non-null root quickly. Other complex logic should be handled in
onLoadChildren()

When onLoadChildren() is called on the root media ID, the result must contain a FLAG_PLAYABLE child.

MediaBrowserService should return the most recently played media item when they receive an
EXTRA_RECENT query. The value returned should be an actual media item rather than generic function.

MediaBrowserService must provide an appropriate MediaDescription with a non-empty title and
subtitle. It should also set an icon URI or an icon bitmap.

The system retrieves the following information from the MediaSession's MediaMetadata, and displays
it when it is available:

METADATA_KEY_ALBUM_ART_URI METADATA_KEY_TITLE METADATA_KEY_ARTIST METADATA_KEY_DURATION (If the
duration isn’t set the seek bar doesn't show progress)

The media player shows the elapsed time for the currently playing media, along with a seek bar which
is mapped to the MediaSession PlaybackState.
