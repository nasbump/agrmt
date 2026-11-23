package proto

import (
	"strings"
	"testing"
)

func TestDecodeRejectsMissingType(t *testing.T) {
	if _, err := Decode([]byte(`{"name":"x"}`)); err == nil {
		t.Fatal("缺少 type 字段时应返回错误")
	}
	if _, err := Decode([]byte(`not-json`)); err == nil {
		t.Fatal("非法 JSON 应返回错误")
	}
}

func TestEncodeDecodeRoundTrip(t *testing.T) {
	code := 7
	in := Message{
		Type:   TypeExit,
		Group:  "g1",
		Name:   "s1",
		Gen:    "abc",
		Cols:   120,
		Rows:   40,
		Code:   &code,
		Status: StatusExited,
		Err:    "e",
	}
	raw, err := Encode(in)
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	out, err := Decode(raw)
	if err != nil {
		t.Fatalf("解码失败: %v", err)
	}
	if out.Type != in.Type || out.Group != in.Group || out.Name != in.Name ||
		out.Gen != in.Gen || out.Cols != in.Cols || out.Rows != in.Rows ||
		out.Status != in.Status || out.Err != in.Err {
		t.Fatalf("往返结果不一致: %+v", out)
	}
	if out.Code == nil || *out.Code != code {
		t.Fatalf("退出码丢失: %+v", out.Code)
	}
}

func TestSessionInfoEncode(t *testing.T) {
	m := Message{
		Type:  TypeSessions,
		Group: "g",
		Sessions: []SessionInfo{
			{Name: "a", Status: StatusOnline, Cols: 80, Rows: 24, Updated: 123},
			{Name: "b", Status: StatusOffline, Cols: 100, Rows: 30, Updated: 456},
		},
	}
	raw, err := Encode(m)
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	out, err := Decode(raw)
	if err != nil {
		t.Fatalf("解码失败: %v", err)
	}
	if len(out.Sessions) != 2 || out.Sessions[0].Name != "a" || out.Sessions[1].Status != StatusOffline {
		t.Fatalf("会话列表往返不一致: %+v", out.Sessions)
	}
}

func TestNilCodeOmitted(t *testing.T) {
	raw, err := Encode(Message{Type: TypeHello, Group: "g", Name: "n"})
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	if strings.Contains(string(raw), `"code"`) {
		t.Fatalf("空退出码不应被序列化: %s", raw)
	}
}
