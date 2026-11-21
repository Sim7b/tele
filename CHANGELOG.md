# Changelog

## [0.3.38](https://github.com/Sim7b/tele/compare/v0.3.37...v0.3.38) (2026-11-21)

### Bug Fixes

* **dispatcharr-plugin:** no runner left behind by an unloaded plugin, no Celery task ([1a13f27](https://github.com/Sim7b/tele/commit/1a13f2791d093cf1673d9f3baf405971e2806028))

## [0.3.37](https://github.com/Sim7b/tele/compare/v0.3.36...v0.3.37) (2026-11-21)

### Bug Fixes

* Dispatcharr channels that never started, and a failing plugin slowing the app ([6337037](https://github.com/Sim7b/tele/commit/63370377154576c202fdd8660f2f3fea737cca56))
* **dispatcharr-plugin:** quieter loads and probe runs ([9750b87](https://github.com/Sim7b/tele/commit/9750b8794c59de4c8a6d31eadf245f57410705a6))

## [0.3.36](https://github.com/Sim7b/tele/compare/v0.3.35...v0.3.36) (2026-11-16)

### Features

* **dispatcharr-plugin:** daily budgets, safer posters and the TVs to update ([f531733](https://github.com/Sim7b/tele/commit/f531733f49a10ab68257e61a76b8a55d58c35216))
* profiles with a code, provider failover, buffer and language settings ([498d4e9](https://github.com/Sim7b/tele/commit/498d4e9fb5254c22cbcc7ec44bd45dc637104ca4))

### Performance Improvements

* startup profile generated on an emulator ([3909b35](https://github.com/Sim7b/tele/commit/3909b3503ac6ff8e19e72daba2a43320b8a6caf9))

## [0.3.35](https://github.com/Sim7b/tele/compare/v0.3.34...v0.3.35) (2026-11-16)

### Features

* **dispatcharr-plugin:** an optional TheIntroDB key in the Settings tab ([e3ac321](https://github.com/Sim7b/tele/commit/e3ac321992f878d602d7e5341f9ffccdb1ec63b6))
* **dispatcharr-plugin:** related titles, TheIntroDB segments and a Settings tab for the keys ([76e4af5](https://github.com/Sim7b/tele/commit/76e4af597e5a9e78502150144407431c637a4cd8))
* **vod:** you may also like, TheIntroDB intro and credits, a richer next episode card ([66c4b3f](https://github.com/Sim7b/tele/commit/66c4b3f9bec8f59f57166e7d3d0b1f6dbc5cb6b9))

## [0.3.34](https://github.com/Sim7b/tele/compare/v0.3.33...v0.3.34) (2026-11-16)

### Performance Improvements

* **dispatcharr-plugin:** ready catalog, light posters and episodes loaded in advance ([dddaac7](https://github.com/Sim7b/tele/commit/dddaac77fcb47ea8779abdb4fb8680a6f3994ff4))
* **vod:** the catalog and the light posters of the Télé API plugin ([5659b42](https://github.com/Sim7b/tele/commit/5659b42f8abd454492246a795df6e2fd552b7fcd))

## [0.3.33](https://github.com/Sim7b/tele/compare/v0.3.32...v0.3.33) (2026-11-16)

### Features

* choose the immersive or the light classic style at the first launch ([3608935](https://github.com/Sim7b/tele/commit/3608935b86e3c821437c8cb88d02df731bbc7220))
* **dispatcharr-plugin:** TMDB facts looked up once on the server for every TV ([e48bfdb](https://github.com/Sim7b/tele/commit/e48bfdb9637508b6fc6d07f78e703d586f897028))

### Bug Fixes

* **vod:** TMDB ratings only, and only from 50 votes ([eca822e](https://github.com/Sim7b/tele/commit/eca822e53ec808e76db8ef53305317c1e4a19945))

### Performance Improvements

* **vod:** TMDB facts from the Dispatcharr server instead of a TMDB lookup per title on each TV ([2461997](https://github.com/Sim7b/tele/commit/24619973bb44d2b5f474a543bdecb8e395cb9c23))

## [0.3.32](https://github.com/Sim7b/tele/compare/v0.3.31...v0.3.32) (2026-11-15)

### Features

* **dispatcharr-plugin:** OpenSubtitles with the key of the server for every TV ([34e53f7](https://github.com/Sim7b/tele/commit/34e53f7a15b43fcd2af71812d1333b61bef769e1))
* **vod:** subtitles through the Dispatcharr server when the TV has no OpenSubtitles key ([83e1afd](https://github.com/Sim7b/tele/commit/83e1afdddf85c1e21983e204c9069ba193701ba7))
* **vod:** the file played first is in the language of the TV, then the best quality ([d7ff79d](https://github.com/Sim7b/tele/commit/d7ff79df7662e3b7d3588d81780f6f04ae11d3e7))

## [0.3.31](https://github.com/Sim7b/tele/compare/v0.3.30...v0.3.31) (2026-11-15)

### Features

* **dispatcharr-plugin:** groups served to the TVs, TMDB confidence, backup, targeted messages and VOD stats ([040165e](https://github.com/Sim7b/tele/commit/040165e19566a50301922c22d108642102ef4124))
* **vod:** only the groups the Dispatcharr admin serves, and a Popular on the server row ([1bc1d0d](https://github.com/Sim7b/tele/commit/1bc1d0d2e076101b784c253e801b4e498d0c929a))

### Bug Fixes

* **vod:** groups with the Arabic code alone are in Arabic ([3be8c65](https://github.com/Sim7b/tele/commit/3be8c6519d798e539df2534df1d93cf756aa7d61))

## [0.3.30](https://github.com/Sim7b/tele/compare/v0.3.29...v0.3.30) (2026-11-15)

### Features

* **dispatcharr-plugin:** TMDB ids, background probes and a dashboard admin page ([e04d401](https://github.com/Sim7b/tele/commit/e04d4016460bde9438a2017a5f28f63bdd44fba1))
* **vod:** Canal+ among the first three services in French, and no white box for an opaque logo ([9276b60](https://github.com/Sim7b/tele/commit/9276b60163d70e6a6554ecae0997637cbe10b1d3))
* **vod:** group the Dispatcharr titles by the TMDB ids of the Télé API plugin ([0a6fb10](https://github.com/Sim7b/tele/commit/0a6fb101d718803fa247a98e8682edc9313e7d3b))

## [0.3.29](https://github.com/Sim7b/tele/compare/v0.3.28...v0.3.29) (2026-11-15)

### Features

* **dispatcharr-plugin:** list of the Télé TVs in the admin page ([cdd27a7](https://github.com/Sim7b/tele/commit/cdd27a79a1e93f87b2167bac5759d40d2460f3de))
* **dispatcharr-plugin:** measure the VOD languages with ffprobe, Télé page for admins ([0802901](https://github.com/Sim7b/tele/commit/080290128314ef83fb794784d798e5db652b989b))
* **dispatcharr-plugin:** request limit per user, provider names for admins ([f71b0a2](https://github.com/Sim7b/tele/commit/f71b0a2b9ba103a862159409ee1d0883c4df4dd4))
* **dispatcharr-plugin:** viewing statistics in the admin page ([737e9c5](https://github.com/Sim7b/tele/commit/737e9c5cfc6dab451638f3015ba6410d2e2dec41))
* **dispatcharr:** message of the Dispatcharr admin on the home screen ([45f0b5a](https://github.com/Sim7b/tele/commit/45f0b5adebd21726cabb29be94c59083c184e6ff))
* **dispatcharr:** replay through the Dispatcharr catch-up ([997bd3d](https://github.com/Sim7b/tele/commit/997bd3de3c68f8d8c1c8468931196515fb6101af))
* **dispatcharr:** streams that failed lately are tried last ([6bf05a6](https://github.com/Sim7b/tele/commit/6bf05a655ddfd03ffb52898fd422a1fc1e4764ea))
* **dispatcharr:** sync between the TVs of a Dispatcharr user ([2047994](https://github.com/Sim7b/tele/commit/20479943575119444d3ec613feb06a0ce91e40fc))
* **vod:** languages measured on the server are labelled as such ([58724f8](https://github.com/Sim7b/tele/commit/58724f82c34cc42404d32e762cf4bb81531e45db))
* **vod:** movie sheets measured on demand with ffprobe on the server ([4c71879](https://github.com/Sim7b/tele/commit/4c7187972828cef5199c705b37a091aeea314604))

### Bug Fixes

* **dispatcharr-plugin:** ffprobe limited to network protocols ([c262d3f](https://github.com/Sim7b/tele/commit/c262d3f28dd5bdb52494f9be8d6c9e69a306dbef))
* **dispatcharr-plugin:** mature content hidden for users who hide it ([61f7cb2](https://github.com/Sim7b/tele/commit/61f7cb2795415641dab8777039d40e95004a20d2))
* **dispatcharr-plugin:** probes never reach the local network ([931e2d9](https://github.com/Sim7b/tele/commit/931e2d9a60c082cc7df0b037dec559722798e85d))
* **dispatcharr:** the version of Télé goes to the Dispatcharr server only ([77d18f4](https://github.com/Sim7b/tele/commit/77d18f4fd6c1419b1f5468bc5bcbe208ef2f7b3a))
* server message state declared before the first sync ([6de2ad3](https://github.com/Sim7b/tele/commit/6de2ad3e1147ef06a7fb6ca17b3c8f7dd1f516a9))

### Performance Improvements

* **dispatcharr:** compact guide of the Télé API plugin, 304 while unchanged ([da1d519](https://github.com/Sim7b/tele/commit/da1d51972ed5259fe6a10ef03558f25de7c06d0f))
* **dispatcharr:** current and next programs before the guide is loaded ([4afa3fb](https://github.com/Sim7b/tele/commit/4afa3fb7855313b9a78b00b21796a4c3465065da))

## [0.3.28](https://github.com/Sim7b/tele/compare/v0.3.27...v0.3.28) (2026-11-15)

### Features

* **vod:** Arabic groups in Latin letters are subtitled in Arabic ([06295e6](https://github.com/Sim7b/tele/commit/06295e6a2b79779fdd2175c87dadf23146a1b59c))
* **vod:** better estimate of the audio and subtitle languages ([ec1e830](https://github.com/Sim7b/tele/commit/ec1e830702d2eff0ba2293e27196791e3ddb4048))

## [0.3.27](https://github.com/Sim7b/tele/compare/v0.3.26...v0.3.27) (2026-11-15)

### Features

* **dispatcharr-plugin:** add the Télé API plugin for Dispatcharr ([28954ee](https://github.com/Sim7b/tele/commit/28954ee7ac1e2de9bfd04cd431d1fc55695fc727))
* **dispatcharr:** close the sessions this TV left open when no connection is free ([fa39520](https://github.com/Sim7b/tele/commit/fa39520e45d8d08edc136b602fc17742ff2da35f))
* **dispatcharr:** use the Télé API plugin for channels and free connections ([968ab5d](https://github.com/Sim7b/tele/commit/968ab5dea272b25d9096a66046857c1b267ebeff))

### Bug Fixes

* **dispatcharr:** probe the Télé API plugin without logging a missing plugin ([a25be3c](https://github.com/Sim7b/tele/commit/a25be3c32af2d4cb6fd5582983ff6f2638f0cff0))

## [0.3.26](https://github.com/Sim7b/tele/compare/v0.3.25...v0.3.26) (2026-11-08)

### Features

* **vod:** streaming services and genres screens, faster library ([2e7b154](https://github.com/Sim7b/tele/commit/2e7b15462e132e183a4e3ec7a6662efd3671377c))

### Bug Fixes

* **guide:** the live preview keeps its 16:9 shape ([e46df67](https://github.com/Sim7b/tele/commit/e46df67004180532121bacfc2f5da26f452656eb))
* **playback:** live channels no longer jump back minutes after a drop ([479dbdc](https://github.com/Sim7b/tele/commit/479dbdcb9f5706e54b2ea2932e704ac2d1c22fbc))
* **ui:** sheet buttons never squeezed, home spotlight lower and simpler ([49af1fa](https://github.com/Sim7b/tele/commit/49af1faf14f3deea1960380e363ced7c808daf2c))

## [0.3.25](https://github.com/Sim7b/tele/compare/v0.3.24...v0.3.25) (2026-11-08)

### Bug Fixes

* **update:** download updates on demand and always confirm the install ([28791f8](https://github.com/Sim7b/tele/commit/28791f825f7b1159daff3f9f33f69fb22a898b83))

## [0.3.24](https://github.com/Sim7b/tele/compare/v0.3.23...v0.3.24) (2026-11-08)

### Features

* immersive movies, series and guide, more TMDB lists, file quality ([ba377c5](https://github.com/Sim7b/tele/commit/ba377c52f69bd090b832c88653b3cc771dfa624a))

## [0.3.23](https://github.com/Sim7b/tele/compare/v0.3.22...v0.3.23) (2026-11-07)

### Features

* **vod:** movies and series sections, file choice, category themes and a sports catalog ([87c2333](https://github.com/Sim7b/tele/commit/87c2333ec2eb5de9f207a647a91d56f5efe3a0bf))

## [0.3.22](https://github.com/Sim7b/tele/compare/v0.3.21...v0.3.22) (2026-11-07)

### Features

* **home:** featured home in the style of the Apple TV app, with glass tabs and lifting cards ([0b3b46a](https://github.com/Sim7b/tele/commit/0b3b46ab7eaedf9d278440de2b3b61502091f960))

## [0.3.21](https://github.com/Sim7b/tele/compare/v0.3.20...v0.3.21) (2026-11-07)

### Bug Fixes

* **home:** immersive home side menu, header fading, sharper artwork ([43beb4b](https://github.com/Sim7b/tele/commit/43beb4b9ab09765c7e3ac0e8f01d84b588ba9304))

## [0.3.20](https://github.com/Sim7b/tele/compare/v0.3.19...v0.3.20) (2026-11-07)

### Features

* immersive home, followed sport, guide preview, Trakt and Dolby passthrough setting ([0452d3a](https://github.com/Sim7b/tele/commit/0452d3a74103e2f811df8a29eaaf3890d2c378eb))

## [0.3.19](https://github.com/Sim7b/tele/compare/v0.3.18...v0.3.19) (2026-11-07)

### Features

* recordings, M3U replay, reminder notifications, stream failover, file backup and time-aware search ([5d5beb2](https://github.com/Sim7b/tele/commit/5d5beb2442b9ec83669cf01e538717a6c107307b))

## [0.3.18](https://github.com/Sim7b/tele/compare/v0.3.17...v0.3.18) (2026-11-06)

### Features

* **player:** pause live TV, frame rate matching, volume leveling, Play next row and voice requests ([7c2eae9](https://github.com/Sim7b/tele/commit/7c2eae9b635a82afd41e7d8b9a2b822182921361))

### Bug Fixes

* **cast:** Dispatcharr channels sent as fragmented MP4 or HLS ([6f24be3](https://github.com/Sim7b/tele/commit/6f24be30abe03695b2142b9dc8b9292447198edb))

## [0.3.17](https://github.com/Sim7b/tele/compare/v0.3.16...v0.3.17) (2026-11-06)

### Features

* parental control, trailers, corner channel and continue watching on the home ([3be3d95](https://github.com/Sim7b/tele/commit/3be3d95b02a00fa474bee95772680373c6de4afe))

## [0.3.16](https://github.com/Sim7b/tele/compare/v0.3.15...v0.3.16) (2026-11-06)

### Features

* settings rows, movie shelves, ratings and background updates ([ce929ec](https://github.com/Sim7b/tele/commit/ce929ecdeb1cc0a12ede3dd5e701c5b1670c0bac))

## [0.3.15](https://github.com/Sim7b/tele/compare/v0.3.14...v0.3.15) (2026-11-06)

### Features

* **channels:** lists of channels besides the favorites ([134de12](https://github.com/Sim7b/tele/commit/134de12633aa474a3c7885fb8a2571fbbf301fc8))
* **player:** optional playback statistics ([fe2a3af](https://github.com/Sim7b/tele/commit/fe2a3af6ce1b44f66949cc7672c59f1fe069d567))
* **search:** programs of the guide and movies and series in the search ([0836d68](https://github.com/Sim7b/tele/commit/0836d68c5626b3c0ca74352101444c7d91b30044))
* **vod:** progressive first load, daily update of the added titles only, new categories suggested ([16e9512](https://github.com/Sim7b/tele/commit/16e95122aa982696a82ab3373cf37b75db3a1890))
* **vod:** skip the intro of an episode ([a7a5a13](https://github.com/Sim7b/tele/commit/a7a5a1341fda6403805d33e95cb505a49832d28d))

## [0.3.14](https://github.com/Sim7b/tele/compare/v0.3.13...v0.3.14) (2026-11-05)

### Features

* **vod:** choose the categories to load, faster library, select all buttons ([491b48c](https://github.com/Sim7b/tele/commit/491b48c635c4cc8233d56223c323d1d1499c4eb8))

## [0.3.13](https://github.com/Sim7b/tele/compare/v0.3.12...v0.3.13) (2026-11-01)

### Features

* phones and tablets, with a Chromecast remote mode ([5e6b2ff](https://github.com/Sim7b/tele/commit/5e6b2ffae77594ddf6910ebb9fd866d5f5ed18b0))

## [0.3.12](https://github.com/Sim7b/tele/compare/v0.3.11...v0.3.12) (2026-11-01)

### Features

* **guide:** guide grid, reminders and replay ([722f644](https://github.com/Sim7b/tele/commit/722f6440d3a45a30106d4618098c04bae7729241))

## [0.3.11](https://github.com/Sim7b/tele/compare/v0.3.10...v0.3.11) (2026-11-01)

### Features

* **vod:** Plex-like player, playlist languages and OpenSubtitles ([9f5724e](https://github.com/Sim7b/tele/commit/9f5724ee5b5641af1bed3ed3adda49fe31ae53ef))

### Bug Fixes

* **manifest:** let the TV launcher open the app again ([1b4e2bb](https://github.com/Sim7b/tele/commit/1b4e2bb2158ef4a9a036b3df6b9492d332afdce2))
* **player:** open the chosen channel and handle full provider connections ([12f2681](https://github.com/Sim7b/tele/commit/12f268118f70b5c9837521d26d1eef2bc0bf63f0))
* **security:** add gradle.lockfile for app/build.gradle.kts ([80f09fe](https://github.com/Sim7b/tele/commit/80f09fe2dbf941045fff33126c92697d6dde5245))
* **security:** autofix Android components with exported attribute active ([191e9b0](https://github.com/Sim7b/tele/commit/191e9b003000c6880b4da32368d9d8818e165fd4))
* **ui:** keep the remote focus when a screen replaces another ([22e11df](https://github.com/Sim7b/tele/commit/22e11df74ed716c82be3768fa3a32a5b6849d0f6))

## [0.3.10](https://github.com/Sim7b/tele/compare/v0.3.9...v0.3.10) (2026-10-28)

### Features

* **vod:** movies and series library ([2e7a51d](https://github.com/Sim7b/tele/commit/2e7a51dd314209d9d21f5ea008c77fc8ace88e67))

### Performance Improvements

* **storage:** envelope encryption for the encrypted caches ([1028bb2](https://github.com/Sim7b/tele/commit/1028bb264b578a74cf5b9a943cc714dc541479f5))

## [0.3.9](https://github.com/Sim7b/tele/compare/v0.3.8...v0.3.9) (2026-10-27)

### Features

* **settings:** settings by section and a configurable preview delay ([195668d](https://github.com/Sim7b/tele/commit/195668dbe379595f72ae766fc342dd8ec293dc94))

### Performance Improvements

* keep the XMLTV guide on the device and lighten the display updates ([7166d5c](https://github.com/Sim7b/tele/commit/7166d5ccffdd3fc39e0747565c741edb60811b22))

## [0.3.8](https://github.com/Sim7b/tele/compare/v0.3.7...v0.3.8) (2026-10-27)

### Bug Fixes

* **startup:** leave the welcome screen without waiting for the XMLTV guide ([c7f1208](https://github.com/Sim7b/tele/commit/c7f120892e3024ee6c429c50a6de81394dc5a9c5))

## [0.3.7](https://github.com/Sim7b/tele/compare/v0.3.6...v0.3.7) (2026-10-27)

### Features

* **bouquet:** Belgium, Switzerland and Canada bouquets, list of the unrecognized channels ([e8b9de2](https://github.com/Sim7b/tele/commit/e8b9de2eb8a1835a9cbc042ce47e115780914aa1))
* **home:** reorder the sources and the categories ([f6819c2](https://github.com/Sim7b/tele/commit/f6819c276a098a49a10f441864ef493e3308212e))
* **player:** audio track, subtitles and picture format options ([9f9ec96](https://github.com/Sim7b/tele/commit/9f9ec9634e9d013fc461238e872adf69d550e45d))
* **settings:** copy the configuration to another TV on the local network ([6c7fe87](https://github.com/Sim7b/tele/commit/6c7fe87ddb308f707a214e1d78227d2364398ee5))

### Bug Fixes

* **android:** drop the forced orientation and disable the legacy backup ([b06676d](https://github.com/Sim7b/tele/commit/b06676d380c6accd12f018459c62796fbf68e2cb))
* **bouquet:** use the 2025 Arcom free-to-air numbering ([c160814](https://github.com/Sim7b/tele/commit/c16081482d7788abe9a45304abbe6bad339a77b2))

## [0.3.6](https://github.com/Sim7b/tele/compare/v0.3.5...v0.3.6) (2026-10-26)

### Features

* **home:** preview the selected channel without sound ([18796a1](https://github.com/Sim7b/tele/commit/18796a10a63821f6d38acd4e1e26a85a24788c1b))

## [0.3.5](https://github.com/Sim7b/tele/compare/v0.3.4...v0.3.5) (2026-10-26)

### Bug Fixes

* **network:** connect to TLS 1.3-only servers on Android 9 and older ([0171da2](https://github.com/Sim7b/tele/commit/0171da2b9bc3a90d2475beed076a563c3b491bf1))

## [0.3.4](https://github.com/Sim7b/tele/compare/v0.3.3...v0.3.4) (2026-10-26)

### Features

* **bouquet:** group channels and categories with a French reference bouquet ([125a32b](https://github.com/Sim7b/tele/commit/125a32b11575a5e0f3b159c11c9d91df6d989ed4))
* **onboarding:** show a quick start guide on the first launch ([2971ce7](https://github.com/Sim7b/tele/commit/2971ce75bbda5283da92f8fd301efa0862cbbe91))
* **player:** favor a stream quality in automatic mode ([c08413c](https://github.com/Sim7b/tele/commit/c08413c01fb5552597a2fd2e74060ac5ecbe2084))
* **sources:** enable several sources at once and rename categories ([a9d7024](https://github.com/Sim7b/tele/commit/a9d70249f400a71d191fb55280e5c9029c11171f))

### Bug Fixes

* **bouquet:** crash at startup, category synonyms and version labels ([1193605](https://github.com/Sim7b/tele/commit/1193605e91234c7e973c6efe220aaee844202c52))

## [0.3.3](https://github.com/Sim7b/tele/compare/v0.3.2...v0.3.3) (2026-10-26)

### Features

* **player:** reconnect automatically when a stream drops ([cc534a3](https://github.com/Sim7b/tele/commit/cc534a39cad066cc21666e500203c8727d54b85a))
* **player:** switch channels with the remote digits ([5f81956](https://github.com/Sim7b/tele/commit/5f81956d470f8d9eb94d267e108bb92a60b66e5f))

### Bug Fixes

* **player:** stop the endless spinner on broken live streams ([c22bf36](https://github.com/Sim7b/tele/commit/c22bf36b6c9519101a6ccd9c5f68faf9020ad039))
* **security:** encrypt the stored credentials ([594b056](https://github.com/Sim7b/tele/commit/594b056aa42675e8437635729832c4a41a1cea20))
* **security:** refuse plain HTTP to GitHub and TVMaze ([44ff651](https://github.com/Sim7b/tele/commit/44ff65165491f70556ff7c2ce2cf2aab3d70357b))

### Performance Improvements

* ship a baseline profile for the app code ([f9369b1](https://github.com/Sim7b/tele/commit/f9369b1b4c5ed2c094dced25dab48233ef71e1a8))

## [0.3.2](https://github.com/Sim7b/tele/compare/v0.3.1...v0.3.2) (2026-10-26)

### Features

* **search:** search channels by text or voice ([04e7f7f](https://github.com/Sim7b/tele/commit/04e7f7fb60d8ba99714c48a8c9559f9734aaa3ed))

### Bug Fixes

* **home:** move down from the favorites to the categories ([2c8ce1c](https://github.com/Sim7b/tele/commit/2c8ce1cf55824b6146164a70d3c24585aba78741))
* **search:** restore the focus on the opened channel after playback ([039a133](https://github.com/Sim7b/tele/commit/039a133b2ab65a86877d9966dbef7e9828e2e24f))

## [0.3.1](https://github.com/Sim7b/tele/compare/v0.3.0...v0.3.1) (2026-10-26)

### Features

* **update:** install new versions from the GitHub releases ([25d37b7](https://github.com/Sim7b/tele/commit/25d37b74d97067306d78eb46bcca4c376e8be432))

## [0.3.0](https://github.com/Sim7b/tele/compare/v0.2.0...v0.3.0) (2026-10-26)

### Features

* **branding:** rename the app to Télé ([01f3f7f](https://github.com/Sim7b/tele/commit/01f3f7f511bca86bac2671cad00b6df115bd69a9))

### Bug Fixes

* **playback:** play chosen Dispatcharr streams through its proxy ([73333a5](https://github.com/Sim7b/tele/commit/73333a5be220e1a056d7257543b9019f37b9f559))

## [0.2.0](https://github.com/Sim7b/tele/compare/v0.1.2...v0.2.0) (2026-10-26)

### Features

* **home:** show free Dispatcharr playback slots ([5a7fbaf](https://github.com/Sim7b/tele/commit/5a7fbafa3f6a59d406573f8f83ae0997a3f277ec))

## [0.1.2](https://github.com/Sim7b/tele/compare/v0.1.1...v0.1.2) (2026-10-26)

### Bug Fixes

* keep channel ids unique and the guide up to date ([bf98400](https://github.com/Sim7b/tele/commit/bf98400566ba81fff019235bb537bc02f04748e3))
* localize the fallback group and share the HTTP pool with images ([54047a6](https://github.com/Sim7b/tele/commit/54047a63cabf662c73ae31cece04245e7ef67a33))
* stream large provider lists and steady the playback ([7b3ebec](https://github.com/Sim7b/tele/commit/7b3ebec850c0b1e69f30b50e13f47e1137d5c3f4))

### Performance Improvements

* keep large channel lists smooth and stop cancelled downloads ([45a2390](https://github.com/Sim7b/tele/commit/45a239042759e4775c7b772130b0598011118edd))
* move the channel cache and logo tones out of the preferences ([9ef615d](https://github.com/Sim7b/tele/commit/9ef615d2e78152c9a27866d9e00c853745d01b1c))

## [0.1.1](https://github.com/Sim7b/tele/compare/v0.1.0...v0.1.1) (2026-10-26)

### Bug Fixes

* **ci:** pass the bot Client ID to create-github-app-token ([c35f643](https://github.com/Sim7b/tele/commit/c35f643398ed2f9358a29be61b8713384f6985c3))

## [0.1.0](https://github.com/Sim7b/tele/compare/v0.0.0...v0.1.0) (2026-10-25)

### Features

* add the Télé IPTV player for Android TV ([7c1a4ef](https://github.com/Sim7b/tele/commit/7c1a4eff34c7b17d3895510b62486aaddb02ac02))
* **i18n:** localize the interface in English and French ([3339638](https://github.com/Sim7b/tele/commit/3339638acc65c9882e696e464ed0da8ff2a4800a))

### Bug Fixes

* **security:** bound the size of server responses, playlists and guides ([d65660b](https://github.com/Sim7b/tele/commit/d65660b6ccf624cd9db09c385ef45a4367d1f602))
* **security:** keep response bodies out of error messages and logs ([a0b972a](https://github.com/Sim7b/tele/commit/a0b972a9eff29754ffbb61892fcf698f540a3ed5))
* **security:** keep source credentials out of Android backups ([e5d00af](https://github.com/Sim7b/tele/commit/e5d00af1d5c19b75f4d3721af4fdbd8537b61f51))
* **security:** send the Dispatcharr API key to its server origin only ([05e570d](https://github.com/Sim7b/tele/commit/05e570d76d7a27af845211dd830a094a42322672))
