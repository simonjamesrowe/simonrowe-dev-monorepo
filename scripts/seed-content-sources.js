// Seed initial content sources for news & events aggregation
db = db.getSiblingDB('simonrowe');

const sources = [
  {
    name: 'Spring Blog',
    baseUrl: 'https://spring.io/blog',
    feedUrl: 'https://spring.io/blog.atom',
    sitemapUrl: null,
    sourceType: 'BLOG',
    scrapeStrategy: 'RSS',
    active: true,
    lastFetchedAt: null,
    lastError: null
  },
  {
    name: 'AI Native Dev',
    baseUrl: 'https://ainativedev.io',
    feedUrl: null,
    sitemapUrl: 'https://ainativedev.io/sitemap.xml',
    sourceType: 'NEWS',
    scrapeStrategy: 'SITEMAP_HTML',
    active: true,
    lastFetchedAt: null,
    lastError: null
  },
  {
    name: 'Rundown AI',
    baseUrl: 'https://www.rundown.ai',
    feedUrl: null,
    sitemapUrl: 'https://www.rundown.ai/sitemap.xml',
    sourceType: 'NEWS',
    scrapeStrategy: 'SITEMAP_HTML',
    active: true,
    lastFetchedAt: null,
    lastError: null
  },
  {
    name: 'London Java Community',
    baseUrl: 'https://www.meetup.com/londonjavacommunity',
    feedUrl: 'https://www.meetup.com/londonjavacommunity/events/rss/',
    sitemapUrl: null,
    sourceType: 'EVENTS',
    scrapeStrategy: 'RSS',
    active: true,
    lastFetchedAt: null,
    lastError: null
  },
  {
    name: 'Tessl Events',
    baseUrl: 'https://tessl.io/events',
    feedUrl: null,
    sitemapUrl: null,
    sourceType: 'EVENTS',
    scrapeStrategy: 'HTML',
    active: true,
    lastFetchedAt: null,
    lastError: null
  },
  {
    name: 'Dan Vega',
    baseUrl: 'https://www.danvega.dev/blog',
    feedUrl: null,
    sitemapUrl: null,
    sourceType: 'BLOG',
    scrapeStrategy: 'HTML_LISTING',
    active: true,
    lastFetchedAt: null,
    lastError: null
  }
];

sources.forEach(source => {
  const existing = db.content_sources.findOne({ name: source.name });
  if (!existing) {
    db.content_sources.insertOne(source);
    print('Inserted: ' + source.name);
  } else {
    print('Already exists: ' + source.name);
  }
});

print('Content sources seeding complete.');
