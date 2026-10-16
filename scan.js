const fs = require('fs');
const base = 'D:/ProjetosDev/Noesis Lab/app/src/main/java/com/example/';
const files = [
  'viewmodel/NoesisViewModel.kt',
  'data/NoesisRepository.kt',
  'network/GroundingAndAiService.kt',
  'ui/screens/FieldSimulationScreen.kt',
  'ui/screens/NarrativeOracleScreen.kt',
  'ui/screens/SystemsAndByokScreen.kt',
  'ui/screens/ArchitecturalMapScreen.kt',
  'ui/components/ChatThread.kt',
  'util/AppLog.kt'
];
const out = [];
for (const f of files) {
  const lines = fs.readFileSync(base + f, 'utf8').split('\n');
  lines.forEach((l, i) => {
    const m = l.match(/"([^"]{4,})"/);
    if (!m) return;
    const s = m[1];
    if (!/[A-Za-z]{3}/.test(s)) return;
    if (/^[a-z_.]+$/.test(s)) return;
    if (/[à-ÿ]/.test(s)) return;
    if (/^\//.test(s)) return;
    if (/^(en|pt|es|fr|internal|cpu|gpu|user|assistant|noesis|isDark)$/i.test(s)) return;
    if (/^(com\.example|https?:|application\/|text\/|model\.task|LICENSE|MIT)$/i.test(s)) return;
    out.push(f + ':' + (i + 1) + '  ' + s.slice(0, 120));
  });
}
console.log(out.join('\n'));
console.log('--- candidatos sem acento (provavelmente PT-only): ' + out.length);
