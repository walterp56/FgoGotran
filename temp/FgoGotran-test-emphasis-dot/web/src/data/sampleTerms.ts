export type TermPreviewRow = {
  jp: string;
  cn: string;
  category: string;
  aliases?: string;
  source: "character" | "term";
};

export const sampleTermRows: TermPreviewRow[] = [
  {
    jp: "マシュ・キリエライト",
    cn: "玛修·基列莱特",
    category: "character",
    aliases: "マシュキリエライト",
    source: "character"
  },
  {
    jp: "アルトリア・ペンドラゴン",
    cn: "阿尔托莉雅·潘德拉贡",
    category: "character",
    aliases: "アルトリアペンドラゴン",
    source: "character"
  },
  {
    jp: "ネロ・クラウディウス",
    cn: "尼禄·克劳狄乌斯",
    category: "character",
    aliases: "ネロクラウディウス",
    source: "character"
  },
  {
    jp: "カルデア",
    cn: "迦勒底",
    category: "place",
    aliases: "Chaldea",
    source: "term"
  },
  {
    jp: "カルデアス",
    cn: "迦勒底亚斯",
    category: "game_term",
    aliases: "CHALDEAS",
    source: "term"
  },
  {
    jp: "ノウム・カルデア",
    cn: "新迦勒底",
    category: "place",
    source: "term"
  },
  {
    jp: "人理修復",
    cn: "人理修复",
    category: "game_term",
    source: "term"
  },
  {
    jp: "特異点",
    cn: "特异点",
    category: "game_term",
    source: "term"
  }
];
