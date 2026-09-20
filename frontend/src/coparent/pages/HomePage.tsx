const HomePage = () => {
  return (
    <main className="min-h-screen bg-slate-950 text-slate-100">
      <section className="mx-auto max-w-4xl px-6 py-20">
        <p className="text-sm uppercase tracking-[0.3em] text-slate-400">CoParent UI</p>
        <h1 className="mt-4 text-4xl font-semibold leading-tight sm:text-5xl">
          Start from a clean, modern React + Vite foundation.
        </h1>
        <p className="mt-6 text-lg text-slate-300">
          This scaffold follows the frontend standards: Tailwind, shadcn/ui, Radix UI, TanStack
          Query, Zustand, and Auth0.
        </p>
        <div className="mt-10 flex flex-wrap gap-3">
          <span className="rounded-full border border-slate-800 bg-slate-900 px-4 py-2 text-xs uppercase tracking-wide text-slate-300">
            Tailwind CSS
          </span>
          <span className="rounded-full border border-slate-800 bg-slate-900 px-4 py-2 text-xs uppercase tracking-wide text-slate-300">
            React Router
          </span>
          <span className="rounded-full border border-slate-800 bg-slate-900 px-4 py-2 text-xs uppercase tracking-wide text-slate-300">
            TanStack Query
          </span>
        </div>
      </section>
    </main>
  );
};

export default HomePage;
