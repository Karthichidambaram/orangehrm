# OrangeHRM Selenium Regression Suite

<!-- Replace Karthichidambaram throughout this file. -->
[![CI](https://github.com/Karthichidambaram/orangehrm/actions/workflows/ci.yml/badge.svg)](https://github.com/Karthichidambaram/orangehrm/actions/workflows/ci.yml)
[![Nightly Regression](https://github.com/Karthichidambaram/orangehrm/actions/workflows/nightly-regression.yml/badge.svg)](https://github.com/Karthichidambaram/orangehrm/actions/workflows/nightly-regression.yml)

UI regression suite for [OrangeHRM](https://opensource-demo.orangehrmlive.com),
built as a study in test framework architecture rather than as a collection of
scripts.

**[Latest Allure report →](https://Karthichidambaram.github.io/orangehrm/)**

---

## Stack

| Concern | Choice | Why this one |
|---|---|---|
| Language | Java 21 | LTS; records make test-data DTOs clean without Lombok |
| Browser automation | Selenium 4.46 | Selenium Manager resolves drivers, so no WebDriverManager |
| Test runner | TestNG 7.12 | Data providers and XML-driven parallelism; JUnit 5 has no equivalent to suite-level `parallel="tests"` |
| Reporting | Allure 2.35 | Step-level detail, trend history, screenshot attachments |
| Build | Maven 3.9 | Surefire's TestNG integration is the path of least resistance |
| Test data | Jackson + Datafaker | JSON reads well in diffs; Datafaker keeps records unique on a shared sandbox |
| Logging | Log4j2 | Thread name in the pattern, which parallel runs need |

## Architecture

```
src/main/java/com/orangehrm/
├── base/          BaseTest, BasePage
├── driver/        DriverFactory (builds), DriverManager (ThreadLocal holder)
├── config/        ConfigReader + enum keys
├── listeners/     TestListener, RetryAnalyzer, RetryTransformer
├── pages/         page objects, grouped to mirror OrangeHRM's left nav
├── components/    NavigationMenu, Header, ToastMessage
├── models/        Employee, LoginCredentials — records
└── utils/         waits, screenshots, data readers

src/test/java/com/orangehrm/tests/   TestNG classes only, one package per module
src/test/resources/suites/           smoke.xml, regression.xml, cross-browser.xml
```

### Design decisions worth defending

- **Framework in `src/main`, tests in `src/test`.** Enforces a one-way
  dependency: tests call the framework, never the reverse. Violations become
  compile errors instead of code review arguments.
- **`ThreadLocal<WebDriver>`, never `static WebDriver`.** A static driver field
  is the single most common framework-killing bug — once threads run in
  parallel they steal each other's browsers.
- **Driver creation split from driver storage.** `DriverFactory` changes when
  Chrome ships a new flag; `DriverManager` changes approximately never.
- **Listeners registered via `ServiceLoader`**
  (`META-INF/services/org.testng.ITestNGListener`), not `@Listeners`. Applies
  globally including single-method IDE runs, where screenshot-on-failure is
  most useful.
- **Parallelism configured in `testng.xml` only**, not in Surefire. Both can
  set it, which gives you two places to look when thread counts surprise you.
- **Tests create their own data.** The demo instance is public, shared, and
  resets periodically, so asserting on pre-existing records is not an option.

## Running

```bash
mvn clean test                      # default: regression, Chrome
mvn test -Psmoke                    # smoke suite
mvn test -Dbrowser=firefox -Dheadless=true
mvn test -Pcross-browser            # parallel Chrome + Firefox
mvn allure:serve                    # report in a local browser
```

Any property in `config.properties` can be overridden with `-D`; system
properties win over file values.

## Pipeline

Three tiers, separated by cost and blast radius:

| Tier | Trigger | What runs | Duration |
|---|---|---|---|
| 1 | every push / PR | `compile` + `test-compile` | ~1 min |
| 2 | PR to `main` | smoke suite, headless Chrome | ~3 min |
| 3 | nightly 02:00 IST | full regression, Chrome + Firefox, Allure published | ~15 min |

The full suite deliberately does not run per-push: the target is a shared
public sandbox, UI tests are slow, and a red X on every commit teaches you to
ignore red X's.

## Progress log

| Date | Stage | What landed |
|---|---|---|
| | 1 | Project scaffold, `pom.xml`, CI pipeline |
| | 2 | Config reader, driver factory, base classes |
| | 3 | Listeners, retry analyzer, screenshot-on-failure |
| | 4 | Login flow — page object + data-driven tests |
| | 5 | PIM: add / search / delete employee |
| | 6 | Admin: user management |
| | 7 | Leave: apply and approve |
| | 8 | Parallel cross-browser tuning |

## Known limitations

- Tests run against a shared public demo instance, so some flakiness is
  environmental rather than a framework defect. Containerising OrangeHRM is
  the planned fix.
- Retry is capped at 1. Retries hide flakiness; a suite where 30 tests pass on
  retry is a broken suite that looks green.
