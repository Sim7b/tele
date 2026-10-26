# Changelog

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
