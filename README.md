# RefBoard

A Pinterest-style viewer for pornpics.com, built for finding pose and angle references by tag. gallery-dl runs inside the app (via Chaquopy) to find galleries and image links; images are streamed and cached, not saved, unless you download a board.

## Build from your phone
1. Create a new GitHub repo and upload everything in this folder (keep the folder structure, including `.github` and `keystore`).
2. Open the **Actions** tab. The "Build APK" workflow runs on every push (or press **Run workflow**).
3. When it finishes, open the run and download **RefBoard-apk**. Unzip it and install the APK.

Builds are signed with the key in `keystore/`, so new builds install over old ones without losing your boards.

## Using it
- **Explore:** search words like "kneeling" or "side view", or tap a suggestion. Long-press a tile to save the whole gallery to a board.
- **Gallery:** tags, categories, models and channel are tappable. Long-press images to multi-select, then pin them.
- **Viewer:** pinch or double-tap to zoom, swipe between images, pin or save one image.
- **Boards:** filter a board by tag, long-press to remove pins, and use the download button to save the board to `Pictures/RefBoard/<board name>`.
- **Bug icon (Explore):** runs gallery-dl on any URL and shows the raw result. Copy that output when reporting a problem.
- You can also share a pornpics link from your browser into the app.
