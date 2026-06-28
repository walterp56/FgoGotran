import { ExternalLink } from "lucide-react";
import { apiProviders } from "@/data/site";

const priorityLabel = {
  "mainland-first": "推荐优先",
  optional: "可选",
  advanced: "进阶"
};

export function ProviderGrid() {
  return (
    <div className="provider-grid">
      {apiProviders.map((provider) => (
        <article className="provider-card" key={provider.id}>
          <div className="provider-card-header">
            <div>
              <p className={`provider-priority ${provider.priority}`}>
                {priorityLabel[provider.priority]}
              </p>
              <h3>{provider.name}</h3>
            </div>
            <span className="provider-short">{provider.shortName}</span>
          </div>
          <dl className="provider-details">
            <div>
              <dt>推荐模型</dt>
              <dd>{provider.recommendedModel}</dd>
            </div>
            <div>
              <dt>API 地址</dt>
              <dd>{provider.endpoint}</dd>
            </div>
          </dl>
          <p>{provider.bestFor}</p>
          <p className="muted">{provider.regionNote}</p>
          <div className="provider-actions">
            {provider.consoleUrl ? (
              <a href={provider.consoleUrl} target="_blank" rel="noreferrer">
                控制台
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            ) : null}
            {provider.docsUrl ? (
              <a href={provider.docsUrl} target="_blank" rel="noreferrer">
                文档
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            ) : null}
          </div>
        </article>
      ))}
    </div>
  );
}
