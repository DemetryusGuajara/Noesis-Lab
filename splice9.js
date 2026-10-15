const fs = require('fs');
const target = 'D:/ProjetosDev/Noesis Lab/app/src/main/java/com/example/ui/screens/ArchitecturalMapScreen.kt';
let src = fs.readFileSync(target, 'utf8');

const lines = src.split(/\r?\n/);
// Draw body = lines 193..(index of the closing "                }" that ends the Canvas lambda)
let start = -1;
for (let i = 0; i < lines.length; i++) {
  if (lines[i].trim() === ') {' && lines[i + 1] && lines[i + 1].includes('val w = size.width')) { start = i; break; }
}
if (start < 0) { console.error('start not found'); process.exit(1); }
let end = -1;
for (let i = start + 2; i < lines.length; i++) {
  if (lines[i] === '                }') { end = i; break; }
}
if (end < 0) { console.error('end not found'); process.exit(1); }

const rep = [
  '                ) {',
  '                    drawMentalSpaces(',
  '                        zones = uiState.diagnosedZones,',
  '                        selectedZoneId = selectedDiagnosed?.zone?.zoneId,',
  '                        textMeasurer = textMeasurer,',
  '                        palette = spacesPalette,',
  '                        showText = true,',
  '                        showGrid = true,',
  '                        alpha = 1f,',
  '                        phase = archPhaseState.value,',
  '                        scale = 1f',
  '                    )'
];
lines.splice(start, end - start + 1, ...rep);

src = lines.join('\n');
src = src.replace(
  'import androidx.compose.ui.Modifier',
  'import androidx.compose.ui.Modifier\nimport com.example.ui.components.drawMentalSpaces\nimport com.example.ui.components.rememberSpacesPalette'
);
src = src.replace(
  '    val textMeasurer = rememberTextMeasurer()\n',
  '    val textMeasurer = rememberTextMeasurer()\n    val spacesPalette = rememberSpacesPalette()\n'
);
fs.writeFileSync(target, src);
console.log('OK, removed', end - start + 1, 'lines ->', rep.length);
