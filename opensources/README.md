# opensources

Reference source for Potassium features, kept here so the upstream code behind a module can be
read next to the module. Nothing in this folder is compiled: the Gradle source set is
`src/main/java`, so these files are reference material only. Only plain source was downloaded —
no jars, no binaries, no build scripts were executed from any of these projects.

Every file was fetched from the raw GitHub URL of the commit listed below, so the copies can be
diffed against upstream at any time.

## FreeLook

`src/main/java/com/potassium/module/FreeLook.java` is based on the first project, with the camera
handling from the second and the third used to cross-check the approach.

### 1. freel00k-mit — MIT

- Upstream: https://github.com/itz-intellij/FreeL00k (Forge 1.8.9)
- Commit: `2ba122e829e11bbe7cc2b942bc61f308da7aed44`
- License: MIT (see `LICENSE`)
- Used for: reading the mouse a second time with `Mouse.getDX/getDY`, the sensitivity curve
  `(sens * 0.6 + 0.2)^3 * 8 * 0.15`, accumulating camera angles, the frozen
  `rotationYawHead`/`renderYawOffset`, the `EntityViewRenderEvent.CameraSetup` override, forcing
  third person and the yaw limit. This is the event based reference the module follows, because
  Potassium is event based and has no mixin loader.
- Not copied: its Forge `Configuration`/GUI wiring, its key binding, its toggle mode.

### 2. freelook-unlicense — Unlicense (public domain)

- Upstream: https://github.com/cxntered/Freelook (Forge 1.8.9)
- Commit: `d58353651880ded02310f29e9d7784409e793083`
- License: Unlicense (see `LICENSE`)
- Used for: cross-checking the perspective save/restore and the FOV restore, and confirming the
  mixin approach (not used here). No code is copied from it.

### 3. hyplook — no license file in the repository

- Upstream: https://github.com/TheAdamNew/Hyplook (Forge 1.8.9)
- Commit: `12c29b40eb9f702e8e9c7bb02449cf67372006ca`
- License: none published. Because of that it is kept as a reading reference only; nothing from it
  is copied into Potassium.
- Used for: cross-checking the mouse capture guard (`inGameHasFocus`, `Display.isActive`), holding
  `thirdPersonView` against F5, and the `+180` yaw convention of `CameraSetup`.

## Safety review

Static review of every file in this folder, done by hand and with a grep for the usual problem
calls (`java.net`, `URL`, sockets, `exec`, `ProcessBuilder`, `Runtime`, `Class.forName`,
`System.load/loadLibrary`, `Base64`, `MessageDigest`, `setAccessible`, `defineClass`, file writes,
`Desktop`, `Robot`, `System.exit`):

- No matches. Every file is client side camera/input code.
- No networking, no process execution, no class loading, no file access, no obfuscated strings, no
  base64 blobs, no native libraries.
- The only reflection-free, Forge-standard hooks used are `EntityViewRenderEvent.CameraSetup`,
  `TickEvent`, `Mouse`/`Display` and (in the mixin project) mixin annotations that Potassium does
  not load.
- Only `.java`/`.kt`/`LICENSE`/`README.md` text files were downloaded. No jars.

## SHA-256 of the files in this folder

```
8f2cc2e68bd8f53c93528218f02ff6a09364dc14ea94ffce00ca8a7a930693aa  freel00k-mit/LICENSE
f6e5a59d3f7165c1af18d9e867cfcaf9baa5f5106bac6a65427786e010c71aff  freel00k-mit/src/main/java/com/freelook/mod/FreeLookHandler.java
3140000a4c31e5a6d06f0e527c0c7645029ae5c0ce5df4499955586e6b15d4d9  freel00k-mit/src/main/java/com/freelook/mod/FreelookMod.java
36d5cc6a46480deec4c2116ef90ce878c47162496fc1d1c05666f93944e4246d  freel00k-mit/src/main/java/com/freelook/mod/ModConfig.java
7e12e5df4bae12cb21581ba157ced20e1986a0508dd10d0e8a4ab9a4cf94e85c  freelook-unlicense/LICENSE
5a0fa9c127ce911420e3301e10a2ddcfeb10c420571d9fb4486d9001f514a34b  freelook-unlicense/src/main/kotlin/dev/cxntered/freelook/Freelook.kt
7ef1048f1a66904a4f3e214b6ccbf1afbd0e9127687aaf51274fca3edf836d51  freelook-unlicense/src/main/java/dev/cxntered/freelook/mixin/MinecraftMixin.java
5693abe9cb0cc58c7792c55e013fdce9e5e290af4df7e9d0adf6794065f516a0  freelook-unlicense/src/main/java/dev/cxntered/freelook/mixin/render/ActiveRenderInfoMixin.java
9b7ab9910a14d3ed51a0e9d573cb74d7b9a34f2d888cef14a114606f224c7391  freelook-unlicense/src/main/java/dev/cxntered/freelook/mixin/render/EntityRendererMixin.java
9a8ad8d425fe7f5239ba0db2295cf488f2c3948ea64aa6095f7f886444c577b2  freelook-unlicense/src/main/java/dev/cxntered/freelook/mixin/render/RenderManagerMixin.java
75679efc2f3eb27cd13b9dff4b583e6bc5ba7d3598faa88917184cd5666c89ec  hyplook/src/main/java/me/theadamnew/hyplook/handler/HyplookHandler.java
3eb71de47372b541c8e1bf12ea8a9dedc45888cda52fab748b888b0543574c36  hyplook/src/main/java/me/theadamnew/hyplook/asm/hooks/CameraHooks.java
```

## VirusTotal

Not submitted. The VirusTotal API rejects anonymous calls (it answered `401` for every lookup,
`429` for the web UI endpoint), and submitting a file needs a free account API key that is not
configured for this machine. Two things are worth knowing before submitting:

- VirusTotal indexes files by hash, and raw source text fetched from GitHub is usually not in its
  database, so a lookup of the hashes above would most likely come back "not found" without saying
  anything about safety. The static review above is the stronger check for source.
- If a release jar from any of these projects is ever used, submit that jar, not the source.

With a key set as `VT_API_KEY` the whole folder can be checked in one go:

```bash
# hash lookup (source files are probably unknown to VT; a hit shows the analysis)
find opensources -type f -exec sh -c 'for f do h=$(sha256sum "$f" | cut -d" " -f1); echo -n "$h "; curl -s -o /dev/null -w "%{http_code}\n" -H "x-apikey: $VT_API_KEY" "https://www.virustotal.com/api/v3/files/$h"; done' sh {} +

# upload a file for analysis
curl -s --request POST --url https://www.virustotal.com/api/v3/files \
  --header "x-apikey: $VT_API_KEY" --form file=@opensources/freel00k-mit/src/main/java/com/freelook/mod/FreeLookHandler.java
```
