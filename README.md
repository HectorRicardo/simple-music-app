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

The state of a player associated to `MediaSession` is represented by two instances attached to
the `MediaSession`:

- An instance of `PlaybackState`: describes the player's current operational state
    - State: Playing/Paused/Buffering/Stopped
    - Player position
    - Valid controller actions that can be handled in the current state. There are two types of
      actions:
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

**Question: why do we need to introduce yet another layer, the `MediaBrowser`? Doesn't
the `MediaController` suffice?**

Two reasons:

1. Because we forcefully need a service (`MediaBrowserService`) to implement the client-server
   approach for audio apps, we also need the `MediaBrowser`. By itself, the `MediaController` is not
   enough: we need the `MediaBrowser` because it is the only entity capable of communicating with
   a `MediaBrowser`
   service, hence we can't ditch it.
2. Follow-up on the first reason: a media `MediaController` is used for both client-server
   architectures (for audio apps) and single-activity architectures (for video apps). Had
   the `MediaController` been the only necessary component for client-server architectures, the
   video apps would've been doomed.

A `MediaBrowserService` provides two features:

- It makes it easy for companion devices to discover your app, create their own `MediaController`,
  connect to your `MediaSession`, and control playback, without accessing your app's UI activity at
  all.
- It also provides an optional browsing API that lets clients query the service and build out a
  representation of its content hierarchy, which might represent playlists, a media library, etc..

### Note: Use Compat classes (NOTE: Where should I place this paragraph?)

The recommended implementation of media sessions and media controllers are the classes
`MediaSessionCompat` and `MediaControllerCompat`. When you use these compat classes, you can remove
all calls to `registerMediaButtonReceiver()` and any methods from `RemoteControlClient`.

### How to setup a `MediaBrowserService` with a `MediaSession`?

1. Declare the `MediaBrowserService` with an intent-filter in the manifest:

   ```xml
   <service
     android:name=".SimpleMusicService"
     android:exported="false"> <!-- For simplicity, our service won't be called outside this app -->
     <intent-filter>
       <action android:name="android.media.browse.MediaBrowserService" />
     </intent-filter>
   </service>
   ```

2. Do the following in the service's `onCreate()` method:

    1. Instantiate a `MediaSession`.
    2. Create and initialize instances of `PlaybackState` and `MediaMetadata` and assign them to the
       `MediaSession` (caching the builders for reuse)
        - In order for external media buttons to work, the `PlaybackState` must contain an action
          matching the intent that the media button sends. This applies to all states.
    3. Create an instance of `MediaSession.Callback` and assign it to the `MediaSession`.
    4. Set the media session token.

    ```java
    public class SimpleMusicService extends MediaBrowserServiceCompat {
      // private static final String MY_MEDIA_ROOT_ID = "media_root_id";
      // private static final String MY_EMPTY_MEDIA_ROOT_ID = "empty_root_id";

      private MediaSessionCompat mediaSession;
      private PlaybackStateCompat.Builder playbackStateBuilder;

      @Override
      public void onCreate() {
        super.onCreate();

        // Create a MediaSessionCompat
        mediaSession = new MediaSessionCompat(this, SimpleMusicService.class.getSimpleName());

        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
        playbackStateBuilder = new PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY |
                    PlaybackStateCompat.ACTION_PLAY_PAUSE);
        mediaSession.setPlaybackState(playbackStateBuilder.build());

        // MySessionCallback() has methods that handle callbacks from a media controller
        mediaSession.setCallback(new MySessionCallback());

        // Set the session's token so that client activities can communicate with it.
        setSessionToken(mediaSession.getSessionToken());
      }
    }
    ```

### How to handle client connections the `MediaBrowserService`'s content hierarchy?

Access permissions to the `MediaBrowserService` are controlled through `onGetRoot()` method. This
method receives the client package name, the client UID, and a `Bundle` of hints as parameters. You
use these parameters to define logic that determines whether to grant permissions and, how much of
the content hierarchy the client should be allowed to browse.

Depending on the outcome you want, you can return one of three things from this method:

- Case 1: `null`, which means the connection is refused and permission was not given.
- Case 2: An empty `BrowserRoot` object: the client was granted permissions to connect, but it
  cannot browse the content hierarchy.
- Case 3: A non-empty `BrowserRoot` object, which defines of the content hierarchy from which the
  client is allowed to browse.

`BrowserRoot` objects contain an ID string. It's up to the implementer to define how these IDs
should look like. However, there are 2 special considerations:

- The content hierarchy is an N-ary tree, and each node has a unique ID. Given the ID of a node, it
  should be possible to retrieve the node's children. A `BrowserRoot` represents a node in the tree,
  and the `BrowserRoot`'s ID is the same as the represented node's ID.
- For Case 2: there should be a special, sentinel ID that represents an empty content hierarchy. A
  `BrowserRoot` node will be built using this ID and returned.

The `onGetRoot()` method should return quickly. User authentication and other slow processes should
not run in `onGetRoot()`, but on `onLoadChildren()`.

`onLoadChildren()` provides the ability for a client to build and display a menu of
the `MediaBrowserService's` content hierarchy. If the client was given browsing-access to the
service, it can traverse the content hierarchy by making repeated calls
to `MediaBrowserCompat.subscribe()` to build a local representation of the UI. The `subscribe()`
method calls the service's callback `onLoadChildren()`, which returns a list of `MediaItem` objects.
A `MediaItem`, just as a `BrowserRoot`, represents a node in the content hierarchy tree, and its ID
is the same as the represented node's ID. If you want to get the children of that node,
call `MediaBrowserCompat.subscribe()` again, but now with that node's ID.

Some considerations:

- If browsing was not allowed, then `onLoadChildren` should send a `null` result.
- `MediaItem`s should not contain icon bitmaps. Use a Uri instead by calling setIconUri() when you
  build the MediaDescription for each item.
- Heavy processing and time-consuming business logic can run in `onLoadChildren`. This method
  returns it results not by an actual method return, but by calling `result.sendResult()`.

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
