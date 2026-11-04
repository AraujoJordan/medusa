#!/usr/bin/env python3
"""Real Android reliability/aggregation validation, including repeated-render diagnosis. Run with JAVA_HOME set to JDK 21.
Copies the fixture to a temporary directory; requires installed Android CLI and SDK.
"""
from pathlib import Path
import os,subprocess,json,time
import tempfile, shutil
root=Path(__file__).resolve().parent.parent
base=Path(tempfile.mkdtemp(prefix='medusa-release-validation-'))
(base/'tools').symlink_to(root/'tools', target_is_directory=True)
p=base/'fixtures/multimodule'
shutil.copytree(root/'fixtures/multimodule',p,ignore=shutil.ignore_patterns('build','.gradle','.kotlin'))
env=dict(os.environ)
def write(relative,text):
 f=p/relative;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
write('core/designsystem/src/main/res/values/medusa_probe.xml','<resources><string name="medusa_resource_probe">Library</string></resources>')
write('app/src/main/res/values/medusa_probe.xml','<resources><string name="medusa_resource_probe">App overrides the library resource</string></resources>')
write('core/designsystem/src/main/kotlin/fx/core/designsystem/ResourceContext.kt','''package fx.core.designsystem
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
@Preview(showBackground = true)
@Composable
fun ResourceContextPreview() { Text(stringResource(R.string.medusa_resource_probe)) }
''')
results=[]
def run(args,expected=0):
 start=time.perf_counter();r=subprocess.run(['./gradlew',*args,'--configuration-cache','--console=plain'],cwd=p,env=env,capture_output=True,text=True);log=r.stdout+r.stderr
 Path('/tmp/medusa-resource-step.log').write_text(log)
 if (r.returncode==0)!=(expected==0):raise RuntimeError(log[-9000:])
 result={'args':args,'exit':r.returncode,'seconds':time.perf_counter()-start,'cache_reused':'Reusing configuration cache' in log,'grouped':'rendered through :app' in log}
 results.append(result);print(result,flush=True)
 return log
run(['medusaRecord'])
run(['medusaVerify'])
run(['medusaVerify'])
run([':core:designsystem:medusaVerify','--preview=ResourceContextPreview'],1)
assert list((p/'core/designsystem/build/medusa/failures').rglob('*ResourceContextPreview*'))
run([':core:designsystem:medusaRecord','--preview=ResourceContextPreview'])
run([':core:designsystem:medusaVerify','--preview=ResourceContextPreview'])
run(['medusaVerify','--preview=ResourceContextPreview'],1)
run(['medusaRecord','--preview=ResourceContextPreview'])
run(['medusaVerify'])
write('app/src/main/res/values/medusa_probe.xml','<resources><string name="medusa_resource_probe">A freshly edited application string</string></resources>')
run(['medusaVerify','--preview=ResourceContextPreview'],1)
run(['medusaRecord','--preview=ResourceContextPreview'])
write('core/designsystem/src/main/kotlin/fx/core/designsystem/AddedLater.kt','''package fx.core.designsystem
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.tooling.preview.Preview
@Preview(showBackground = true)
@Composable
fun AddedLaterPreview() { Text("Added after daemon warm-up") }
''')
run(['medusaRecord','--preview=AddedLaterPreview'])
run(['medusaVerify','--preview=AddedLaterPreview'])
run(['medusaDiagnose','--preview=ResourceContextPreview'])
(root/'benchmark/RELIABILITY_VALIDATION.json').write_text(json.dumps(results,indent=2)+'\n')
