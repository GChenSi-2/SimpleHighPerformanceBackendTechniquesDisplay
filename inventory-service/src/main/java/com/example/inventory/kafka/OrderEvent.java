package com.example.inventory.kafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka 消息体：下单事件
 * @Data を付けると、そのクラスに対して Lombok がよく使う定型コードを自動生成します。主に以下です。

getter
setter
toString()
equals()
hashCode()
必須フィールド用コンストラクタ
この OrderEvent だと、手で getter/setter を書かなくても、各フィールドに対して自動で使えるようになります。

補足です。

@NoArgsConstructor
引数なしコンストラクタを生成
@AllArgsConstructor
全フィールドを引数に持つコンストラクタを生成
なのでこのクラスは、Lombok を使って「POJO のボイラープレートを省略している」形です。

大事な点は 1 つです。

これはコメントではない
コンパイル時に Lombok がコードを補ってくれるアノテーション
もし必要なら、次に

このクラスに対して実際にどんなメソッドが生成されるか
Lombok なしで書くとどうなるか
を並べて説明できます。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {
    private String eventId;   // 唯一事件ID，用于幂等
    private String orderNo;
    private String skuId;
    private int quantity;
    private int retryCount;   // 已重试次数
}
