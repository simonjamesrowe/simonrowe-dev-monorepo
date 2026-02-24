// Migration script: Transform Strapi data to Spring Boot schema
const src = db.getSiblingDB('strapi_backup');
const dst = db.getSiblingDB('simonrowe');

// Helper: resolve image ObjectId to Image object
function resolveImage(imageId) {
  if (!imageId) return null;
  const file = src.upload_file.findOne({ _id: imageId });
  if (!file) return null;
  return {
    url: file.url,
    name: file.name,
    width: file.width || null,
    height: file.height || null,
    mime: file.mime,
    formats: null
  };
}

// Helper: resolve image ObjectId to URL string
function resolveImageUrl(imageId) {
  if (!imageId) return null;
  const file = src.upload_file.findOne({ _id: imageId });
  return file ? file.url : null;
}

// 1. Migrate profiles
print('--- Migrating profiles ---');
dst.profiles.drop();
src.profiles.find().forEach(p => {
  dst.profiles.insertOne({
    _id: p._id,
    name: (p.name || '').trim(),
    firstName: (p.name || '').trim().split(' ')[0],
    lastName: (p.name || '').trim().split(' ').slice(1).join(' '),
    title: p.title,
    headline: p.headline,
    description: p.description,
    profileImage: resolveImage(p.profileImage),
    sidebarImage: resolveImage(p.sidebarImage),
    backgroundImage: resolveImage(p.backgroundImage),
    mobileBackgroundImage: resolveImage(p.mobileBackgroundImage),
    location: p.location,
    phoneNumber: (p.phoneNumber || '').trim(),
    primaryEmail: p.primaryEmail,
    secondaryEmail: p.secondaryEmail || '',
    cvUrl: p.cv ? resolveImageUrl(p.cv) : null,
    createdAt: p.createdAt,
    updatedAt: p.updatedAt
  });
});
print('Profiles: ' + dst.profiles.countDocuments());

// 2. Migrate social_medias
print('--- Migrating social_medias ---');
dst.social_medias.drop();
src.social_medias.find().forEach(s => {
  dst.social_medias.insertOne({
    _id: s._id,
    type: s.type,
    name: s.name,
    link: s.link,
    includeOnResume: s.includeOnResume || false,
    createdAt: s.createdAt,
    updatedAt: s.updatedAt
  });
});
print('Social medias: ' + dst.social_medias.countDocuments());

// 3. Migrate tags
print('--- Migrating tags ---');
dst.tags.drop();
src.tags.find().forEach(t => {
  dst.tags.insertOne({
    _id: t._id,
    name: t.name
  });
});
print('Tags: ' + dst.tags.countDocuments());

// 4. Migrate skills (for blog DBRef)
print('--- Migrating skills ---');
dst.skills.drop();
src.skills.find().forEach(s => {
  dst.skills.insertOne({
    _id: s._id,
    name: s.name
  });
});
print('Skills: ' + dst.skills.countDocuments());

// 5. Migrate skill_groups (with embedded skills)
print('--- Migrating skill_groups ---');
dst.skill_groups.drop();
src.skills_groups.find().forEach(sg => {
  const embeddedSkills = (sg.skills || []).map(skillId => {
    const skill = src.skills.findOne({ _id: skillId });
    if (!skill) return null;
    return {
      _id: skill._id,
      name: skill.name,
      description: skill.description || null,
      rating: skill.rating || null,
      order: skill.order || null,
      image: resolveImage(skill.image),
      includeOnResume: skill.includeOnResume || false
    };
  }).filter(s => s !== null);

  dst.skill_groups.insertOne({
    _id: sg._id,
    name: sg.name,
    description: sg.description || null,
    rating: sg.rating || null,
    displayOrder: sg.order || null,
    image: resolveImage(sg.image),
    skills: embeddedSkills
  });
});
print('Skill groups: ' + dst.skill_groups.countDocuments());

// 6. Migrate blogs
print('--- Migrating blogs ---');
dst.blogs.drop();
src.blogs.find().forEach(b => {
  const tagRefs = (b.tags || []).map(tagId => ({
    "$ref": "tags",
    "$id": tagId
  }));
  const skillRefs = (b.skills || []).map(skillId => ({
    "$ref": "skills",
    "$id": skillId
  }));

  dst.blogs.insertOne({
    _id: b._id,
    title: b.title,
    shortDescription: b.shortDescription || null,
    content: b.content || null,
    published: b.published || false,
    featuredImageUrl: resolveImageUrl(b.image),
    createdDate: b.createdAt,
    updatedDate: b.updatedAt,
    tags: tagRefs,
    skills: skillRefs
  });
});
print('Blogs: ' + dst.blogs.countDocuments());

// 7. Migrate jobs
print('--- Migrating jobs ---');
dst.jobs.drop();
src.jobs.find().forEach(j => {
  const skillNames = (j.skills || []).map(skillId => {
    const skill = src.skills.findOne({ _id: skillId });
    return skill ? skill.name : null;
  }).filter(s => s !== null);

  dst.jobs.insertOne({
    _id: j._id,
    title: j.title,
    company: j.company,
    companyUrl: j.companyUrl || null,
    companyImage: resolveImage(j.companyImage),
    startDate: j.startDate,
    endDate: j.endDate || null,
    location: j.location || null,
    shortDescription: j.shortDescription || null,
    longDescription: j.longDescription || null,
    isEducation: j.education || false,
    includeOnResume: j.includeOnResume || false,
    skills: skillNames
  });
});
print('Jobs: ' + dst.jobs.countDocuments());

// 8. Migrate tour steps
print('--- Migrating tourSteps ---');
dst.tourSteps.drop();
src['tour-steps'].find().forEach(ts => {
  dst.tourSteps.insertOne({
    _id: ts._id,
    order: ts.order,
    targetSelector: ts.selector,
    title: ts.title,
    titleImage: resolveImageUrl(ts.titleImage),
    description: ts.description,
    position: ts.position || 'bottom'
  });
});
print('Tour steps: ' + dst.tourSteps.countDocuments());

print('--- Migration complete ---');
print('Collections: ' + dst.getCollectionNames());
