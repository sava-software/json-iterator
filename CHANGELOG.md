# Changelog

## [25.3.0](https://github.com/sava-software/json-iterator/compare/25.2.0...25.3.0) (2026-07-19)


### ⚠ BREAKING CHANGES

* **jsoniter:** JsonIterator.readObject() and readObjField() are removed.
* **jsoniter:** Default `readArray` and `readMap` methods now utilize an optimized sizing logic. Update any custom implementations relying on previous behaviors. Enriched `JsonException` may impact exception handling logic.

### Features

* **build:** Bump sava-build plugin version ([6eaed15](https://github.com/sava-software/json-iterator/commit/6eaed15407505b09b8b93e67480fe67b16a7342e))
* **jsoniter:** add quality gate and mutation ratchet process details ([0efe144](https://github.com/sava-software/json-iterator/commit/0efe1445c3b86e48ad7512f50ff5eca7a5ee71e4))
* **jsoniter:** add quality gate and mutation ratchet process details ([7a3c4eb](https://github.com/sava-software/json-iterator/commit/7a3c4eb05dde14dc629940ef6751e32ccb47c09d))
* **jsoniter:** enhance parsing with sized array and map methods ([db15a1b](https://github.com/sava-software/json-iterator/commit/db15a1b4aad9e15c0b642b1666a94db0ae5d106a))
* **jsoniter:** remove deprecated readObject/readObjField ([4db0000](https://github.com/sava-software/json-iterator/commit/4db0000ad553d5655704b5158b942fbc2a07eb8f))
* **test:** add multibyte scan path and fuzz corpus replay tests ([aa16460](https://github.com/sava-software/json-iterator/commit/aa164606f7b2a55bd7d22c7601565a9e97aa93bc))


### Miscellaneous Chores

* release 25.3.0 ([db236fd](https://github.com/sava-software/json-iterator/commit/db236fdb011cbd0819a292618af7e2844d79699b))

## [25.2.0](https://github.com/sava-software/json-iterator/compare/25.1.3...25.2.0) (2026-07-18)


### ⚠ BREAKING CHANGES

* **jsoniter:** Deprecated methods and interfaces have been removed. Consumers must migrate to the corresponding modern APIs like `applyObject`, `testObject`, or new dispatch handlers before updating.

### Features

* **fuzzing:** add fuzz tests for JSON number/instant parsing and edge cases ([fa0c8dc](https://github.com/sava-software/json-iterator/commit/fa0c8dc7ffeb5b6acb45102b6b07d50087b5a119))
* **jsoniter:** add tests for sub-range reuse with mark/reset ([699726a](https://github.com/sava-software/json-iterator/commit/699726ab936d4012f090452b0c7dc42a8fc120f9))
* **jsoniter:** remove deprecated API for cleanup and consistency ([1a0f3ff](https://github.com/sava-software/json-iterator/commit/1a0f3ff871b06563e397d46c3db5ee263a41de77))


### Miscellaneous Chores

* release 25.2.0 ([016da83](https://github.com/sava-software/json-iterator/commit/016da83ec4df2d48e4184f3420009e9602a56289))

## [25.1.3](https://github.com/sava-software/json-iterator/compare/25.1.2...25.1.3) (2026-07-15)


### Features

* **jsoniter:** add primitive array parsing and enum matcher support ([b58b028](https://github.com/sava-software/json-iterator/commit/b58b0287b7b655980ec30d4912006296d5725971))

## [25.1.2](https://github.com/sava-software/json-iterator/compare/25.1.1...25.1.2) (2026-07-14)


### Features

* **jsoniter:** add primitive array parsing and enum matcher support ([8fc0f60](https://github.com/sava-software/json-iterator/commit/8fc0f60b3402863805b123bf4e55a73cdaa0258b))

## [25.1.1](https://github.com/sava-software/json-iterator/compare/25.1.0...25.1.1) (2026-07-14)


### Features

* **jmh:** add `SourceBench` for data source comparisons and processing costs ([3247f73](https://github.com/sava-software/json-iterator/commit/3247f73cd4295b1b36e27c032d098529c87a5a34))
* **jsoniter:** add `notNull` method to complement `readNull` ([7c68b70](https://github.com/sava-software/json-iterator/commit/7c68b709c299289852ad7e6c66f101edce6b8546))
* **jsoniter:** add collection and map parsing methods ([6cd97dd](https://github.com/sava-software/json-iterator/commit/6cd97ddb89c5255432974a1d304618bada175d44))

## [25.1.0](https://github.com/sava-software/json-iterator/compare/21.1.0...25.1.0) (2026-07-12)


### Features

* **jmh:** add benchmarks for IOC parsing and FieldMatcher dispatch ([2150847](https://github.com/sava-software/json-iterator/commit/2150847b382ef02a0f054f5c32b9a3c56807ca55))
* **jmh:** improve JMH setup and results documentation ([426cb06](https://github.com/sava-software/json-iterator/commit/426cb0697b623c98529b77cd5f93b610416bdce4))
* **jmh:** improve JMH setup and results documentation ([e5d0b10](https://github.com/sava-software/json-iterator/commit/e5d0b1054f885d1da48a7a71dc8b03dcb4069960))
* **jsoniter:** deprecate unused APIs and adjust JVM configuration ([a2d2e2d](https://github.com/sava-software/json-iterator/commit/a2d2e2d90deda085936c1b43053cafa437e5c40f))


### Miscellaneous Chores

* release 25.1.0 ([d912c93](https://github.com/sava-software/json-iterator/commit/d912c93c6e5c98c82d3b28ec3a43617b0e93e3ee))

## [21.1.0](https://github.com/sava-software/json-iterator/compare/21.0.12...21.1.0) (2026-07-11)


### ⚠ BREAKING CHANGES

* **jsoniter:** Stream-based parsing is no longer supported; streams are now read fully into memory. Update usage to pass preloaded buffers or adjust memory handling accordingly.
* **jsoniter:** Changes to UTF-8 handling may alter behavior for certain inputs. Update dependencies as needed.

### Features

* **jsoniter:** add RFC-1123 instant parsing with validation tests ([c3faeda](https://github.com/sava-software/json-iterator/commit/c3faeda4d4feb4cb725a779ec68c653a76a598e4))
* **jsoniter:** enhance escape handling and null skipping, add tests ([accf792](https://github.com/sava-software/json-iterator/commit/accf7921930133380f0c00821f32f3206563c7be))
* **jsoniter:** implement fast decimal-to-double parser ([2eeafe1](https://github.com/sava-software/json-iterator/commit/2eeafe164183ddc4da9dade4b26de06f32194400))
* **jsoniter:** improve UTF-8 handling and add long string tests ([11d31be](https://github.com/sava-software/json-iterator/commit/11d31be5a54a27a28a6e98082712c5cf2df47767))
* **jsoniter:** optimize Base64 decoding and add robustness tests ([76000a7](https://github.com/sava-software/json-iterator/commit/76000a7a9e2d5efdd6dc8fd0749df8fe08987ea1))
* **jsoniter:** optimize field comparison and add tests for tricky cases ([1513563](https://github.com/sava-software/json-iterator/commit/15135639aaf2a9fe84fe63b3d40fb3c54a759d6f))
* **jsoniter:** replace stream-based parsing with full upfront reads ([2c6536e](https://github.com/sava-software/json-iterator/commit/2c6536ea5c19a1771ed79f5887d935861b10652a))
* **tests:** add edge case tests for escapes, containers, and instant parsing ([54dc39a](https://github.com/sava-software/json-iterator/commit/54dc39a8ad02a88ad910dff03d359c6c4976a134))


### Miscellaneous Chores

* release 21.0.12 ([2f1b163](https://github.com/sava-software/json-iterator/commit/2f1b163bf8c5f7082584a008d024abade4be53d0))
* release 21.1.0 ([a3c9168](https://github.com/sava-software/json-iterator/commit/a3c9168387991c8f0e8ea6b01574aefdcbd9da24))
