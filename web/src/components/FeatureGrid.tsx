import type { Feature } from "@/data/site";

type FeatureGridProps = {
  items: Feature[];
};

export function FeatureGrid({ items }: FeatureGridProps) {
  return (
    <div className="feature-grid">
      {items.map((item) => {
        const Icon = item.icon;
        return (
          <article className="feature-card" key={item.title}>
            <div className="feature-icon">
              <Icon size={22} aria-hidden="true" />
            </div>
            <h3>{item.title}</h3>
            <p>{item.body}</p>
          </article>
        );
      })}
    </div>
  );
}
