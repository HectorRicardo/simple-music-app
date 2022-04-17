# Simple Music App

Android app that implements a complete, fully-fledged architecture for a small music player using the Android Media APIs and native C++
code.

The music player is silent: it's just a thread that sleeps for the duration of the song entity it was instructed to play. However, all the flow and the logic of the app is implemented.

The player/thread logic is implemented in native C++ code. This is because the player logic is totally independent of Android: we could, for example, implement a similar app in iOS using this exact same logic/code, the only difference being that we would use iOS media libraries.

The following section are notes that I took while reading https://developer.android.com/guide/topics/media.

## General concepts: Media app overview

This first section talks about basic concepts that apply to media apps. Media apps = both music apps and video apps.

A decent media app is separated into two components:

- A Player that renders the media (audio/video).
- A UI that issues commands to the player (play, pause, etc..) and displays the player's state.
  - Commands are represented as "Transport Controls" in the following diagram. 

<figure>
  <img alt="A basic media app diagram" src="docs_images/controller-session.png">
  <figcaption>Figure 1. Basic media app diagram</figcaption>
</figure>

A decent media app in Android completely separates and decouples these two parts. Why?
Because by doing so, the Player is not condemned to be used exclusively from the app's UI.
Instead, the Player can be controlled also from other places, such as:
- external hardware media buttons
- Google Assistant
- notification bar/lock screen

Android provides two universal classes that decouple these two media app components. Since these
classes are universal, they allow a media app to integrate with any other Android app/component/mechanism
smoothly and consistently.

The two classes are: 1) an instance of `MediaController`, which controls the UI, and 2) an instance of `MediaSession`, which manages the player:

- The `MediaController`:
    - UI communicates exclusively with the `MediaController` (the UI never calls the player or the `MediaSession` directly).
    - Issues player commands to the `MediaSession`. Commands can be either:
        - Built-in, common commands, such as play, pause, stop, and seek.
        - Extensible custom commands, used to define special behaviors unique to your app.
    - Receives callbacks from the `MediaSession` informing about player state changes in order to update the UI.
- The `MediaSession`:
    - Responsible for all communication with the player.
    - The player is only called from the `MediaSession`.
    - Receives command callbacks from the `MediaController`, and forwards these commands to the player.
    - When the player updates its state, sends a callback to the `MediaController` to notify about this update.
 
<figure>
  <img src="docs_images/controller-session-detailed.png" alt="Detailed view of media app architecture">
  <figcaption>Figure 2. Detailed view of media app architecture</figcaption>
</figure>

A `MediaController` can connect to only one `MediaSession` at a time, but a `MediaSession` can connect
with one or more `MediaController`s simultaneously. This allows for your player to be
controlled from your app's UI as well as from other places (Google Assistant, notification bar, etc..).
Each of these "places" creates its own `MediaController` and connects to your app's `MediaSession` the same way.

## Player State

A Player entity has/maintains state, which is divided in two kinds:

- **Playback state**: The player's current operational state. Is represented by an instance of `PlaybackState`, which consists of:
    - State: Playing/Paused/Buffering/Stopped/Error/etc..
    - Position (current progress as displayed in a seekbar)
    - Valid controller actions (both built-in and custom) that can be handled in the current state.
        - These actions define what commands and external hardware media buttons the player will
          respond to in the current state.
    - Active error code and error message, if any. Errors can be fatal or non-fatal:
        -  **Fatal**: Happens when playback is interrupted and cannot resume.
            -  The state will be ERROR (instead of Playing, Paused, etc..)
            -  This error is cleared only when playback isn't blocked anymore.
        - **Non-fatal**: Happens when the player cannot handle a request, but can continue to play.
            - Player remains in a "normal" state (such as Playing or Paused).
            - This error is cleared in the next `PlaybackState` update (or overriden, if a new error comes in).
- **Media metadata**: information about what is currently playing. Is represented as an instance of `MediaMetadata`, which consists of:
    - Name of current artist, album, track
    - Duration of track
    - Album artwork
 
Whenever one of these two states change, the player informs the `MediaSession`, which in turn
informs the `MediaController` of the same change by sending it one of two callbacks:

- `onPlaybackStateChanged()`
- `onMediaMetadataChanged()`

These controller callbacks receive as parameter the new `PlaybackState` or `MediaMetadata`. They are used to
update the UI according to the new state received.

Using the universal classes `PlaybackState` and `MediaMetadata` allows us to represent player state exactly the
same accross all `MediaController`-`MediaSession` connections. There's no need to implement different logic for
every client that tries to connect to your app.

From the next section onwards, we will talk specifically about music apps
(no more talking about video apps, unless explicitly mentioned).

## Design/Architecture of music apps

An audio player does not always need to have its UI visible. Once it begins to play audio, 
the player can run as a background task. The user can switch to another app and work while
continuing to listen.

This design is implemented with a client-server architecture

- The server will be an Android service that will hold the player.
    - An Android service is a long-lived Android component that can run in the background and doesn't need a UI to run.
    - Implemented as a subclass of `MediaBrowserService`.
    - Will hold the `MediaSession` and the player.
- The client is an Activity for the UI.
    - Will hold a `MediaBrowser` that will connect to the `MediaBrowserService`.
    - Will also hold the `MediaController`.

<figure>
  <img src="docs_images/client-service-architecture.png" alt="Client-server architecture for music apps">
  <figcaption>Figure 3. Client-server architecture for music apps</figcaption>
</figure>

**Question: why do we need to introduce yet another layer, the `MediaBrowser`-`MediaBrowserService` pair?
Doesn't the `MediaController`-`MediaSession` pair suffice?**

- The `MediaController`-`MediaSession` pair applies both to audio and video apps,
while the `MediaBrowser`-`MediaBrowserService` pair applies specifically to audio apps only.
  - The `MediaBrowser`-`MediaBrowserService` pair is used to implement to the client-server architecture we've just described, and video apps don't follow this architecture (only audio apps do).
  - The `MediaController`-`MediaSession` pair is not tied to any architecture so it can accomodate both music and video apps.
- We forcefully need an Android service so the music can play in the background. A `MediaSession` is not a service, hence we need `MediaBrowserService`.
  - And because we forcefully need `MediaBrowserService`, we also need its counterpart, the `MediaBrowser`. By itself, the `MediaController` is not enough: we need the `MediaBrowser` because it is the only entity capable of communicating with a `MediaBrowserServce`.

As mentioned in the previous section, having a well-defined `MediaController-MediaSession` architecture allows your app's player (either an audio or video player) to be controlled not only from your app's UI, but also from other places. Now, for music apps, in addition to this advantage, having a well-defined `MediaBrowser`-`MediaBrowserService` architecture has two additional advantages:
- It makes your app discoverable to companion devices like Android Auto and Wear OS.
  - After discovering your app, the companion device can then take advantage of the `MediaController-MediaSession` architecture, that is, it can proceed to create its own `MediaController`, connect to your `MediaSession`, and control playback, without accessing your app's UI activity at all.
- It also provides an optional browsing API that lets clients query the `MediaBrowserService` and build out a
  representation of its **content hierarchy**.
  - The content hierarchy is the full media library available. It might consists of songs or media items organized hierarchically into artists, albums, playlists, etc.. We'll talk more about the content hierarchy in a bit.

## Note: Use Compat classes (NOTE: Where should I place this paragraph?)

The recommended implementation of media sessions and media controllers are the classes
`MediaSessionCompat` and `MediaControllerCompat`. When you use these compat classes, you can remove
all calls to `registerMediaButtonReceiver()` and any methods from `RemoteControlClient`.

## How to setup a `MediaBrowserService` with a `MediaSession`?

1. Create a `MediaBrowserService` file.
2. Declare the `MediaBrowserService` with an intent-filter in the manifest:

   ```xml
   <service
     android:name=".SimpleMusicService"
     android:exported="false"> <!-- For simplicity, our service won't be called outside this app -->
     <intent-filter>
       <!-- Note that the name doesn't require the "Compat" suffix -->
       <action android:name="android.media.browse.MediaBrowserService" />
     </intent-filter>
   </service>
   ```

3. Create a new instance of `PlaybackState.Builder` and assign it to a final instance property of the service.
    - Instead of creating a new builder each time, we will use this builder every time we need to update
      the player's playback state.
    - Since playback state updates happen quite frequently, caching the builder will greatly reduce memory
      consumption
5. Do the following in the service's `onCreate()` method:
    1. Instantiate a `MediaSession`.
    2. Assing an instance of `PlaybackState` to the `MediaSession`:
        - Use the builder created in step 3 and initialize it. 
            - A good way to initialize it is by defining some actions that you want the player to
              respond to in its initial state, such as `ACTION_PLAY` and `ACTION_PLAY_PAUSE`.
        - Build the builder and assign it to the `MediaSession` created in the previous step.
    3. Assign an instance of `MediaSession.Callback` to the `MediaSession`.
        - This instance contains the callbacks that forward to the player the commands issued from the `MediaController`.
        - Examples of callbacks: `onPlay()`, `onPause()`, `onSeekTo()`, `onSkipToNext()`
        - We'll see more about media session callbacks a bit later.
    5. Link the `MediaSession` to the `MediaBrowserService` by setting the media session token.
        - `MediaBrowser`s can then discover this session token when connecting to the `MediaBrowserService`.
        - `MediaController`s will then use the discovered token to communicate with the respective `MediaSession`.

    ```java
    public class SimpleMusicService extends MediaBrowserServiceCompat {
      private final PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder();
      private final MediaSessionCompat.Callback mediaSessionCallbacks = new MediaSessionCompat.Callback() {
        // Implement callbacks that react to commands issued from a MediaController,
        // most likely by forwading these commands to the player.
      };
      
      private MediaSessionCompat mediaSession;

      @Override
      public void onCreate() {
        super.onCreate();

        // Create a MediaSession
        mediaSession = new MediaSessionCompat(this, SimpleMusicService.class.getSimpleName());

        // Set an initial PlaybackState with ACTION_PLAY and ACTION_PLAY_PAUSE
        // so these commands/media buttons can start the player
        mediaSession.setPlaybackState(playbackStateBuilder.setActions(
                PlaybackStateCompat.ACTION_PLAY |
                    PlaybackStateCompat.ACTION_PLAY_PAUSE).build());

        // Set the media session callbacks
        mediaSession.setCallback(mediaSessionCallbacks);

        // Set the session's token so that MediaControllers can discover the session once the
        // MediaBrowsers connect to the service.
        setSessionToken(mediaSession.getSessionToken());
      }
    }
    ```
    
## The Content hierarchy

Before proceeding, let's explain more about the content hierarchy. The content hierarchy is the media
library offered by your app's `MediaBrowserService`. It's a graph of nodes representing submenus
(eg. directories) and media items (e.g. songs) that you organize as you wish. Each node has a unique ID.
This is an example of a very simple content hierarchy:

<figure>
  <img src="docs_images/content_hierarchy_sample.svg" alt="Sample content hierarchy">
  <figcaption>Figure 4. Sample content hierarchy</figcaption>
</figure>

This content graph might translate to something like this in an app: TODO

Depending on the permissions you give, clients connecting to your `MediaBrowserService` can access and browse this
content hierarchy. You can also restrict clients to allow them to browse only a limited subset of
the content hierarchy (starting from a given node). And you can define these permissions on a client-per-client basis.
We'll talk about permissions in the next section.

Every node in the depicted graph above has between 0-N children. Given a node, you can retrieve its children by
looking at the graph. This is the most fundamental characteristic of the content hierarchy. It's also
the only enforced requirement of the graph.

The example in the image is actually a very good example for a content hierarchy. Technically speaking, the
example graph is a [Directed Acyclic Graph (DAG)](https://en.wikipedia.org/wiki/Directed_acyclic_graph), because:
- Its edges have direction, hence "Directed"
  - For example, from *Romantic*, it's indicated that you can "jump" to *Shallow*.
- It doesn't have any cycles, hence "Acyclic".
  - Once you jump to *Shallow*, there's no way you can get back to *Romantic*.
  - This is important since it prevents infinite loops in the browsing algorithm in your app and
    in other apps/mechanisms connecting to your app.

You can see that some items, such as *Viva la Vida*, have two parents. This is completely valid,
and it's a common characteristic of a DAG. (And think about it: some songs can be considered belonging to two genres.
In this example, we consider Coldplay's *Viva la Vida* to be both a pop song and an alternative song).

Although not strictly required by Android, content hierarchies that are DAGs are a best practice for music apps.

**Question: Are the playable items only allowed to be at the last level of the graph?**

Answer: No! You can have a graph like this:

![Allowed graph](docs_images/content_hierarchy_variation1.svg)

Here we consider *Bad Guy (Billie Eilish)* to be so different that the song itself *is* another genre.

Heck, a node can even be both a submenu and a playable item:

![Allowed graph](docs_images/content_hierarchy_variation2.svg).

Here, imagine that by playing "Pop", you hear a narrator saying something like "Welcome to the pop music section".

However, when going through these uncommon use cases, you should follow these 2 best practices
(besides making your content hierarchy a DAG):
- The graph root node (starting point) should not be playable.
- The other starting point nodes from which clients are allowed to browse should not be playable either.

These two are not requirements strictly enforced by Android, but it's good to follow them.

## How to handle client connections to the `MediaBrowserService`?

Permissions to connect to the `MediaBrowserService` and to browse its content hierarchy are
controlled by the service's `onGetRoot()` method. As parameters, this
method receives the identifiers of the client wanting to connect and a `Bundle` of hints. You use these parameters
to define logic that determines whether to grant permissions to the client to reach the service, 
and if so, the subset of the content hierarchy the client should be allowed to browse.

The return type of this method is a `BrowserRoot`, which is an object that has an ID field.
For most cases, you return a `BrowserRoot` whose ID corresponds to the ID of the content hierarchy's node from
which you want to allow the client to browse. (And, as per the best practices stated above, this
node shouldn't be playable). However, there are two special cases:
- If you return `null`, it means the connection is refused and permission was not given.
- If you return a `BrowserRoot` whose ID does not match the ID of any node, it means that
  the client was granted permissions to connect, but it cannot browse the content hierarchy at all.
    - We call such `BrowserRoot` an empty `BrowserRoot` and its ID, an "empty media root id".

The `onGetRoot()` method should return quickly. User authentication and other slow processes should
not run in `onGetRoot()`, but on `onLoadChildren()`, which we explain next.

## How can a client build a representation of your app's content hierarchy?

If the value returned from `onGetRoot` is non-null, a client should now attempt to traverse the service's content hierarchy to build a UI representation of it. (A client should try to do this even if the `BrowserRoot` returned was the empty `BrowserRoot`, because the client doesn't have a way to know that). The client will call the `MediaBrowser`'s `subscribe()` method with the ID of the `BrowserRoot` returned from `onGetRoot`. The `subscribe` method will end up calling the service's `onLoadChildren` method, which will return the children of the node passed in to `subscribe`.

Here's the flow explained in detail. The algorithm is iterative:

1. The client calls the `MediaBrowserCompat.subscribe()` method, passing in the following as parameters:
    - The ID of the node whose children you want to obtain.
        - In the first iteration of the algorithm, this will be the ID of the `BrowserRoot` node returned from `onGetRoot`.
    - A callback that will be executed whenever the service returns the children of the requested node.
        - This callback has a `List<MediaItem>` as a parameter, which is precisely the result sent back by the service.
2. The `subscribe()` method internally ends up calling `onLoadChildren`, forwarding the node ID that it was passed in by the client.
3. `onLoadChildren` looks at the ID of the node passed in. It retrieves the immediate children of the node, and returns them as result.
    - If the ID of the passed-in node is actually the ID of the empty `BrowserRoot` node, then an empty list is returned.
    - Heavy processing, user authentication, and time-consuming business logic can run here. This method is async, meaning that it doesn't return
    with an actual `return`, but by calling `result.sendResult()` (`result` is the second parameter of `onLoadChildren`).
    - The children (instances of `MediaItem`) returned by this method should not contain icon bitmaps. Use a Uri instead by calling `setIconUri()` when you
     build the `MediaDescription` for each item.
4. The callback that was passed in in step 1 is executed on the client's side. This callback has as its parameter the list of children retrieved in the previous step.
    - The client uses this list to partially build (keep building) a menu of the content hierarchy.
5. The client looks at each `MediaItem` in the results.
    - If `MediaItem.isBrowsable()` is true, then the client jumps back to step 1, but now passing the ID of the current `MediaItem`.

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
