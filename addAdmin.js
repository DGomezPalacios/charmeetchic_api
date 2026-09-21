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

  // setCustomUserClaims recibe el UID, no el email: primero se busca al usuario por email.
  // La cuenta debe existir en Firebase (basta con haber iniciado sesión una vez con Google).
  // Uso: node addAdmin.js [email]
  const email = process.argv[2] || 'danigp.93@gmail.com';

  admin.auth().getUserByEmail(email)
    .then(user => admin.auth().setCustomUserClaims(user.uid, { ADMIN: true }).then(() => user))
    .then(user => {
      console.log(`✅ Custom claim ADMIN agregado a ${email} (uid: ${user.uid})`);
      console.log('ℹ️  Cierra sesión y vuelve a entrar en el panel: el ID token actual dura 1 hora y no lo trae.');
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