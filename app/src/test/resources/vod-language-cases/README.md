# VOD language cases

Real cases measured by the Télé API plugin of Dispatcharr: for one file of a category, the languages that
`ffprobe` read in its audio and subtitle tracks. `VodLanguageCasesTest` compares them with what Télé estimates
from the category and file names.

To add cases: in Dispatcharr, open the Télé page (`/api/tele/ui/`, admins) and click **Export as Télé test
cases**, or run the **Export the measured languages** action of the plugin. Drop the file here (any name
ending in `.json`), check it holds nothing you do not want public (category and file names), then run:

```bash
./gradlew testDebugUnitTest --tests 'io.github.sim7b.tele.data.vod.VodLanguageCasesTest'
```

- **Wrong** (fails the test): the estimate names a language the file does not have. Fix the rules in
  `VodLanguages.kt` / `VodNames.kt`, or, when the probed file is not typical of its category, add
  `"skip": "why"` to the case.
- **Missing** (printed, does not fail): a language of the file that the names do not tell. Worth a rule when
  the names do tell it.
