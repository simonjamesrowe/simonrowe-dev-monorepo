db = db.getSiblingDB('simonrowe');

db.profiles.insertOne({
  title: 'Engineering Leader',
  headline: 'PASSIONATE ABOUT AI-NATIVE DEVELOPMENT',
  description: 'Most recently, I have been leading engineering for commercial trading platforms...',
  updatedAt: new Date()
});

db.jobs.insertOne({
  company: 'Global',
  title: 'Head of Engineering',
  shortDescription: 'Leading AI-native transformation and platform modernization.',
  longDescription: 'Head of Engineering for Commercial Trading at Global...',
  skills: ['AWS', 'Kafka', 'Spring Boot'],
  updatedAt: new Date()
});
