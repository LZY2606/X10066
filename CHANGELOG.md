# Changelog

## Unreleased

### 统一 multiplatform 格式、生成代码与规范 fixture 门禁（根级 `verify`）

#### 实现选择

- 新增根级 `./gradlew verify`，聚合三个可独立执行的 buildSrc 门禁任务，
  任一子任务失败即让构建失败（Gradle 默认传播，无 `--continue` 掩盖）：
  - `verifyGeneratedSources`（`buildSrc/src/main/kotlin/tasks/VerifyGeneratedSources.kt`）：
    先执行 `:json-schema-validator:apiDump` 与 `:json-schema-validator-objects:apiDump`，
    再对受控生成路径（`*/api`）执行 `git status --porcelain`；生成物缺失、为空或
    与已提交状态漂移都会失败，并列出漂移条目。任务标记 `outputs.upToDateWhen { false }`，
    因为 git 工作树状态无法声明为任务输入，否则门禁会被 up-to-date 跳过而静默通过。
  - `verifyFixtureParity`（`buildSrc/src/main/kotlin/tasks/VerifyFixtureParity.kt`）：
    动态发现 `verification/fixtures` 下的共通 fixture（含 `optional/` 与
    `optional/format/`），按 target 家族（jvm/js/wasmJs/native）计算每个
    host 可运行 target 的收集数；任一共通 fixture 在某 target 零收集、或跨
    target 收集数不一致都会失败，错误信息包含 fixture 相对路径、target 名与
    各自收集数。无法在本机运行的 target（交叉 native、iOS 模拟器等）不判失败，
    而是在 `build/reports/verify/fixture-parity.txt` 与控制台单独报告为
    target-specific 跳过并附原因。
  - `verifyFormatRegistry`（`buildSrc/src/main/kotlin/tasks/VerifyFormatRegistry.kt`）：
    双向对齐 `FormatAssertionFactory.KNOWN_FORMATS` 与 `internal/formats` 下的
    具体 validator 类（注册表指向的类缺失、或具体 validator 未注册都失败）；
    校验 5 个 draft loader config 的 format 默认开关——legacy draft（4/6/7）要求
    `null` 与 `ANNOTATION_AND_ASSERTION` 同分支，vocabulary draft（2019-09/2020-12）
    要求显式 option 回退到 format-assertion vocabulary 而非硬编码；同时校验导出
    API dump 中 `FormatValidator`、`FormatBehavior` 两个枚举值、
    `FORMAT_BEHAVIOR_OPTION`、`FormatValidationResult`、`withCustomFormat` 均在位。
- 新增根级 `test` 任务：按 host 聚合各子项目可运行的测试任务
  （`jvmTest`、`jsNodeTest`、`wasmJsNodeTest` 加本机 native target），
  此前 `./gradlew test` 直接报 "Task 'test' not found"。浏览器测试（karma）
  需要本地浏览器，刻意不纳入该离线入口。
- 新增独立小 fixture 集 `verification/fixtures/`（JSON-Schema-Test-Suite 格式、
  自带 `$schema`、含 `optional/format` 与 `target-specific/<family>` 分类），
  不依赖 `test-suites/schema-test-suite` submodule，可离线运行。
- 新增运行时门禁 `StandaloneFixtureGateTest`（`test-suites/src/commonTest/.../standalone/`），
  在每个 target 上动态发现并执行这些 fixture：共通 fixture 零收集即失败，
  跳过的 target-specific 家族会打印报告。可单独定位：
  `./gradlew :test-suites:jvmTest --tests "io.github.optimumcode.json.schema.suite.standalone.StandaloneFixtureGateTest"`。
- CI 与本地收敛到同一入口：`.github/workflows/check.yml` 的构建步骤加入 `verify`。
- 所有门禁只使用仓库 wrapper 与锁定依赖（version catalog + `kotlin-js-store/yarn.lock`），
  不访问外网、不依赖本机绝对路径（fixture 路径经环境变量/相对候选解析）、
  不按具体 fixture 文件名特判（全部动态发现）、不使用 sleep。

#### 原覆盖的空白

- 此前没有任何任务校验 `KNOWN_FORMATS` 注册表与 `internal/formats` 下类的双向一致性，
  也没有校验注册表与公开 API dump 的对齐；`apiCheck` 只看 ABI 漂移，
  不管"生成后工作树必须干净"，且根项目连 `check`/`test` 生命周期任务都不完整。
- 规范 fixture 的收集数量此前没有任何跨 target 对账：某个 target 因 source-set
  接线问题静默少收集甚至零收集时，测试仍会绿。
- format 默认开关（`FORMAT_BEHAVIOR_OPTION` 缺省行为）此前只靠人工 review 保证
  5 个 draft 配置一致。

#### 相邻语义的退化保护

- vocabulary draft（2019-09/2020-12）的 format 缺省是规范规定的 annotation-only：
  fixture `verification/fixtures/draft2020-12/format-annotation-default.json` 锁定
  "未声明 format-assertion vocabulary 时非法格式值默认通过"，防止有人把 2020-12
  默认改成断言而破坏规范兼容。
- legacy draft 的默认断言行为由 `verification/fixtures/draft7/format-default.json`
  在每个 target 上运行时锁定。
- `optional/format` fixture 以显式 `ANNOTATION_AND_ASSERTION` 执行，保护
  "显式 option 覆盖 vocabulary 默认"这条路径。

#### 最危险反例与对应回归用例

最危险的反例是**某个 draft 配置的 format 默认开关被悄悄翻转**（例如把 draft7 的
`null` 分支从 `AnnotationAndAssertion` 改成 `AnnotationOnly`）：所有 schema 照样加载、
所有测试任务照样执行，但非法格式值从"拒绝"变成"仅注解"，验证器在用户无感知的情况下
放行非法数据——这是静默的语义弱化，happy path 测试完全发现不了。

对应回归用例有两层：

- 构建时：`verifyFormatRegistry` 解析全部 5 个 draft 配置的 format 分支，
  任一分支偏离共享默认即失败（已用注入翻转实测：构建以
  "format default toggle in .../Draft7SchemaLoaderConfig.kt differs ..." 失败）。
- 运行时：`verification/fixtures/draft7/format-default.json` 断言默认行为下
  非法 `ipv4` 必须 `valid: false`，在每个 host 可运行 target 上由
  `StandaloneFixtureGateTest` 执行（已用翻转期望值实测：测试失败）。
