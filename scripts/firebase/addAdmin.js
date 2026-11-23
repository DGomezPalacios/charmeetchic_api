// API modular de firebase-admin (v10+). Desde la v14 ya no existe la API "namespaced"
// (admin.credential.cert, admin.auth()), por eso se importan las funciones de cada módulo.
const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const fs = require('fs');
const path = require('path');

// Clave de la cuenta de servicio (ignorada por git y por Docker). Por defecto se busca junto a este script
// (scripts/firebase/), sin importar desde qué carpeta se ejecute; FIREBASE_ADMIN_KEY permite indicar otra ruta.
const keyPath = process.env.FIREBASE_ADMIN_KEY
  ? path.resolve(process.env.FIREBASE_ADMIN_KEY)
  : path.join(__dirname, 'charme-et-chic-firebase-admin-key.json');
if (!fs.existsSync(keyPath)) {
  console.error('❌ Archivo no encontrado:', keyPath);
  process.exit(1);
}

const serviceAccount = JSON.parse(fs.readFileSync(keyPath, 'utf8'));

try {
  const app = initializeApp({
    credential: cert(serviceAccount),
    projectId: serviceAccount.project_id
  });
  const auth = getAuth(app);

  // setCustomUserClaims recibe el UID, no el email: primero se busca al usuario por email.
  // La cuenta debe existir en Firebase (basta con haber iniciado sesión una vez con Google).
  // Uso (desde scripts/firebase/): node addAdmin.js [email]   o   npm run add-admin -- [email]
  const email = process.argv[2] || 'danigp.93@gmail.com';

  auth.getUserByEmail(email)
    .then(user => auth.setCustomUserClaims(user.uid, { ADMIN: true }).then(() => user))
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