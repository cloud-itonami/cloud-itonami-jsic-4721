# physai-jsic-4721 — 冷蔵倉庫業（JSIC 4721）の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-jsic-4721`、JSIC 4721 冷蔵倉庫業）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README には "Robotics premise" 節が無い。この actor は冷蔵倉庫の入庫 → 保管 → 出庫ステージングを調整し、
設備制御はしない（README: "Not equipment control"）。ここでは、この actor が調整する物理的な仕事 —— 冷凍庫内のパレット AGV、
ケースピッキングアーム、出荷ドックで待つ冷凍品の昇温（storage-lot 検査が判定するコールドチェーン逸脱）—— を宣言する。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:freezer-pallet-agv-stop` | transport | 800 kg の冷凍品パレットを AGV が冷凍庫通路へ 60 m 運び、ラック端で止まる（霜で制動が効きにくい） | 制動距離 | 1.0 m（estimate） |
| `:carton-depalletise` | manipulator | ケースピッキングアームがパレット最上段のケースを出荷コンベヤへ移す | 肩関節ピークトルク | 450 N·m（estimate） |
| `:frozen-carton-dock-dwell` | thermal | -25 °C の 8 cm 小売ケースが開いた出荷ドック扉のそばで待つ（半厚・対称、中心面で判定） | 中心が -18 °C に達するまでの時間 | ≥ 3600 s（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/coldchain/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。
repo 自身の `test/` も同じ runner で走る。着地時点で 112 tests / 257 assertions / 0 fail）。

## 測って分かったこと・限界（成長の第一候補）

1. **パレット AGV**: 通路速度 1.5 m/s からの制動距離は 0.5 m/s² で 2.25 m、1.0 m/s² で 1.125 m、1.5 m/s² で 0.75 m、3.0 m/s² で 0.375 m。
   限界 1.0 m を守るのに必要な制動減速度は **1.125 m/s²** 以上 —— 霜で路面摩擦が落ちてそれを下回るなら、速度を下げるしかない。
   所要時間は 43.38 s → 42.12 s とほぼ変わらず、転倒余裕は 0.94〜0.63 で転倒は律速ではない。
2. **ケースピッキング**: 肩トルクは 5 kg で 267.2 N·m、15 kg で 385.6 N·m、25 kg で 504.8 N·m。限界 450 N·m に達するケース重量は **20.4 kg**。
3. **ドック待機**: 中心が -18 °C に達する時間はドック空気 -15 °C で 7638 s、-10 °C で 4151 s、-5 °C で 2957 s、0 °C で 2337 s、20 °C で 1374 s。
   1 時間の待機に耐える限界ドック温度は **-8.17 °C** —— 温度管理されていないドック（0 °C 以上）では 40 分もたない。
   solver に潜熱が無い（凍結水の融解熱を無視）ので実際より速く温まる側の見積もりである。repo 自身の `coldchain.facts` の F4 帯
   （-20 °C 以下）はさらに厳しい。
4. **estimate のままの値（成長候補）**:
   - 制動距離 1.0 m → 無人搬送車の安全規格（ISO 3691-4 の検出域・停止距離の要件）か AGV メーカー仕様。
   - 肩トルク 450 N·m → 実際のパレタイジングロボットのデータシート。
   - ドック待機 3600 s と -18 °C → 冷凍食品の取扱い規範（Codex の急速冷凍食品の実施規範、日本冷蔵倉庫協会の基準）を原典で確かめる。
   - 冷凍食品の熱物性（k 1.5、c 2000）、ドック扉付近の熱伝達係数 15 W/m²K、AGV・アームの寸法・質量。
   - solver に足りないもの: 潜熱（相変化）。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-jsic-4721 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-jsic-4721 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
