const admin = require('firebase-admin');
const fs = require('fs');

// Leer el archivo JSON
const keyPath = './charme-et-chic-firebase-admin-key.json';
if (!fs.existsSync(keyPath)) {
  console.error('❌ Archivo no encontrado:', keyPath);
  process.exit(1);
}

const serviceAccount = JSON.parse(fs.readFileSync(keyPath, 'utf8'));

try {
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    projectId: serviceAccount.project_id
  });

  admin.auth().setCustomUserClaims('danigp.93@gmail.com', { ADMIN: true })
    .then(() => {
      console.log('✅ Custom claim ADMIN agregado!');
      process.exit(0);
    })
    .catch(error => {
      console.error('❌ Error:', error);
      process.exit(1);
    });
} catch (error) {
  console.error('❌ Error inicializando:', error.message);
  process.exit(1);
}