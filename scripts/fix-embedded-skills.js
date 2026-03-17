// Migration script: Extract embedded skill objects from skill_groups into proper skill documents
// Runs via mongosh inside the MongoDB container
// Idempotent — safe to run multiple times
//
// Usage:
//   docker exec -i <container> mongosh < scripts/fix-embedded-skills.js

const db = db.getSiblingDB('simonrowe');

print('=== Fix Embedded Skills Migration ===');
print('');

let totalExtracted = 0;
let totalSkipped = 0;
let totalGroupsFixed = 0;

const allGroups = db.skill_groups.find().toArray();
print('Found ' + allGroups.length + ' skill groups to check');
print('');

for (const group of allGroups) {
  const skills = group.skills || [];
  const newSkillIds = [];
  let hasEmbedded = false;

  for (const entry of skills) {
    // If the entry is a string (already a proper ID), keep it
    if (typeof entry === 'string') {
      newSkillIds.push(entry);
      continue;
    }

    // If the entry is an embedded object, extract it into a proper skill document
    if (typeof entry === 'object' && entry !== null && entry.name) {
      hasEmbedded = true;

      // Check if a skill with this name already exists
      const existing = db.skills.findOne({ name: entry.name });
      if (existing) {
        print('  [SKIP] "' + entry.name + '" already exists as skill document (id: ' + existing._id + ')');
        newSkillIds.push(existing._id.toString());
        totalSkipped++;
        continue;
      }

      // Create a new skill document
      const now = new Date();
      const skillDoc = {
        name: entry.name,
        rating: entry.rating || null,
        description: entry.description || null,
        image: entry.image || null,
        displayOrder: entry.displayOrder || 0,
        createdAt: now,
        updatedAt: now
      };

      const result = db.skills.insertOne(skillDoc);
      const newId = result.insertedId.toString();
      newSkillIds.push(newId);
      totalExtracted++;
      print('  [CREATE] "' + entry.name + '" -> skill id: ' + newId);
    }
  }

  if (hasEmbedded) {
    // Update the skill group to use string IDs instead of embedded objects
    db.skill_groups.updateOne(
      { _id: group._id },
      { $set: { skills: newSkillIds } }
    );
    totalGroupsFixed++;
    print('  Updated group "' + group.name + '": ' + newSkillIds.length + ' skill references');
    print('');
  } else {
    print('  Group "' + group.name + '": OK (no embedded objects)');
  }
}

print('');
print('=== Migration Summary ===');
print('Groups checked: ' + allGroups.length);
print('Groups fixed: ' + totalGroupsFixed);
print('Skills created: ' + totalExtracted);
print('Skills already existed: ' + totalSkipped);
print('Total skills in collection: ' + db.skills.countDocuments());
print('=== Migration complete ===');
