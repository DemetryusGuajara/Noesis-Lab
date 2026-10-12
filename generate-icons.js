const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const inputIcon = path.join(__dirname, 'icon.png');
const outputDir = path.join(__dirname, 'app', 'src', 'main', 'res');

// Adaptive icon foreground sizes (safe zone is 66% of total)
// For adaptive icons, the foreground should be the full size but content centered in 66% safe zone
const densities = [
  { folder: 'mipmap-mdpi', size: 48, foregroundSize: 48 },
  { folder: 'mipmap-hdpi', size: 72, foregroundSize: 72 },
  { folder: 'mipmap-xhdpi', size: 96, foregroundSize: 96 },
  { folder: 'mipmap-xxhdpi', size: 144, foregroundSize: 144 },
  { folder: 'mipmap-xxxhdpi', size: 192, foregroundSize: 192 },
];

// Background color
const bgColor = '#1b1d44';

async function generateIcons() {
  console.log('Gerando ícones adaptivos...\n');

  // Ensure output directories exist
  for (const d of densities) {
    const dir = path.join(outputDir, d.folder);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
      console.log(`📁 Criado: ${d.folder}`);
    }
  }

  // Also create mipmap-anydpi-v26 for XML
  const anydpiDir = path.join(outputDir, 'mipmap-anydpi-v26');
  if (!fs.existsSync(anydpiDir)) {
    fs.mkdirSync(anydpiDir, { recursive: true });
  }

  // Generate foreground icons for each density
  for (const d of densities) {
    const outputPath = path.join(outputDir, d.folder, 'ic_launcher_foreground.png');
    
    await sharp(inputIcon)
      .resize(d.foregroundSize, d.foregroundSize, {
        fit: 'contain',
        background: { r: 0, g: 0, b: 0, alpha: 0 }
      })
      .png()
      .toFile(outputPath);
    
    console.log(`✅ ${d.folder}/ic_launcher_foreground.png (${d.foregroundSize}x${d.foregroundSize})`);
  }

  // Generate background (solid color) for xxxhdpi (used as reference)
  // Actually for adaptive icons, background can be just a color in XML, but we'll create a PNG too for compatibility
  const bgOutputPath = path.join(outputDir, 'mipmap-xxxhdpi', 'ic_launcher_background.png');
  await sharp({
    create: {
      width: 192,
      height: 192,
      channels: 4,
      background: bgColor
    }
  })
  .png()
  .toFile(bgOutputPath);
  console.log(`✅ mipmap-xxxhdpi/ic_launcher_background.png (192x192) - cor ${bgColor}`);

  // Generate XML files
  const adaptiveIconXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>`;

  const roundIconXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>`;

  fs.writeFileSync(path.join(anydpiDir, 'ic_launcher.xml'), adaptiveIconXml);
  console.log(`✅ mipmap-anydpi-v26/ic_launcher.xml`);

  fs.writeFileSync(path.join(anydpiDir, 'ic_launcher_round.xml'), roundIconXml);
  console.log(`✅ mipmap-anydpi-v26/ic_launcher_round.xml`);

  // Create colors.xml with the background color if it doesn't exist
  const valuesDir = path.join(outputDir, 'values');
  if (!fs.existsSync(valuesDir)) {
    fs.mkdirSync(valuesDir, { recursive: true });
  }
  
  const colorsXmlPath = path.join(valuesDir, 'colors.xml');
  let colorsXmlContent = '';
  
  if (fs.existsSync(colorsXmlPath)) {
    colorsXmlContent = fs.readFileSync(colorsXmlPath, 'utf8');
    if (!colorsXmlContent.includes('ic_launcher_background')) {
      // Add the color before closing </resources>
      colorsXmlContent = colorsXmlContent.replace('</resources>', `    <color name="ic_launcher_background">#1b1d44</color>\n</resources>`);
      fs.writeFileSync(colorsXmlPath, colorsXmlContent);
      console.log(`✅ values/colors.xml - adicionada cor ic_launcher_background`);
    } else {
      console.log(`ℹ️ values/colors.xml - cor ic_launcher_background já existe`);
    }
  } else {
    colorsXmlContent = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1b1d44</color>
</resources>`;
    fs.writeFileSync(colorsXmlPath, colorsXmlContent);
    console.log(`✅ values/colors.xml - criado com cor ic_launcher_background`);
  }

  console.log('\n🎉 Todos os ícones adaptivos gerados com sucesso!');
  console.log('\nEstrutura criada:');
  console.log('  app/src/main/res/');
  console.log('  ├── mipmap-anydpi-v26/');
  console.log('  │   ├── ic_launcher.xml');
  console.log('  │   └── ic_launcher_round.xml');
  for (const d of densities) {
    console.log(`  ├── ${d.folder}/`);
    console.log(`  │   └── ic_launcher_foreground.png`);
  }
  console.log('  ├── mipmap-xxxhdpi/');
  console.log('  │   └── ic_launcher_background.png');
  console.log('  └── values/');
  console.log('      └── colors.xml (com #1b1d44)');
}

generateIcons().catch(err => {
  console.error('Erro:', err);
  process.exit(1);
});