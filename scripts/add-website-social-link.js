// Migration script: Add website social media link for CV/resume generation
// Runs via mongosh inside the MongoDB container
// Idempotent — safe to run multiple times

const db = db.getSiblingDB('simonrowe');

// ============================================================================
// Idempotency check
// ============================================================================

const existing = db.social_medias.findOne({ type: 'website' });

if (existing) {
  print('Website social media link already exists — updating includeOnResume to true');
  db.social_medias.updateOne(
    { type: 'website' },
    {
      $set: {
        link: 'https://simonrowe.dev',
        includeOnResume: true,
        updatedAt: new Date()
      }
    }
  );
} else {
  print('Adding website social media link');
  db.social_medias.insertOne({
    type: 'website',
    name: 'Website',
    link: 'https://simonrowe.dev',
    includeOnResume: true,
    createdAt: new Date(),
    updatedAt: new Date()
  });
}

print('Website social media link: ' + db.social_medias.findOne({ type: 'website' }).link);
print('Done.');
