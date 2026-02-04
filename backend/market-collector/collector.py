import yfinance as yf
import requests
from PIL import Image, ImageDraw, ImageFont
import os
import datetime
import json

class MarketCollector:
    def __init__(self):
        self.output_dir = "shared-data/market-reports"
        os.makedirs(self.output_dir, exist_ok=True)
        
    def get_market_indices(self):
        """미국 주요 지수 데이터 수집"""
        tickers = {
            "^DJI": "다우존스",
            "^IXIC": "나스닥",
            "^GSPC": "S&P 500",
            "KRW=X": "원/달러 환율"
        }
        
        results = {}
        for ticker, name in tickers.items():
            data = yf.Ticker(ticker)
            hist = data.history(period="2d")
            if len(hist) >= 2:
                close = hist['Close'].iloc[-1]
                prev_close = hist['Close'].iloc[-2]
                change = close - prev_close
                change_pct = (change / prev_close) * 100
                results[name] = {
                    "price": round(close, 2),
                    "change": round(change, 2),
                    "pct": round(change_pct, 2)
                }
        return results

    def get_top_stocks(self):
        """주요 빅테크 종목 등락 수집"""
        stocks = {
            "NVDA": "엔비디아",
            "TSLA": "테슬라",
            "AAPL": "애플",
            "MSFT": "마이크로소프트",
            "GOOGL": "구글"
        }
        
        results = {}
        for ticker, name in stocks.items():
            data = yf.Ticker(ticker)
            hist = data.history(period="2d")
            if len(hist) >= 2:
                close = hist['Close'].iloc[-1]
                prev_close = hist['Close'].iloc[-2]
                change_pct = ((close - prev_close) / prev_close) * 100
                results[name] = round(change_pct, 2)
        return results

    def generate_report_image(self, indices, stocks):
        """수집된 데이터를 바탕으로 리포트 이미지 생성"""
        width, height = 1080, 1920
        background_color = (15, 23, 42)  # 다크 블루 (Slate 900)
        image = Image.new("RGB", (width, height), background_color)
        draw = ImageDraw.Draw(image)
        
        # 폰트 경로 (Windows 기준)
        font_path = "C:/Windows/Fonts/malgun.ttf"
        
        try:
            title_font = ImageFont.truetype(font_path, 80)
            subtitle_font = ImageFont.truetype(font_path, 50)
            content_font = ImageFont.truetype(font_path, 45)
            small_font = ImageFont.truetype(font_path, 35)
        except:
            print("Warning: Malgun Gothic font not found, using default.")
            title_font = ImageFont.load_default()
            subtitle_font = ImageFont.load_default()
            content_font = ImageFont.load_default()
            small_font = ImageFont.load_default()

        # 상단바
        draw.rectangle([0, 0, width, 100], fill=(30, 41, 59))
        draw.text((50, 25), "VALUE PIXEL MORNING", fill=(148, 163, 184), font=small_font)

        # 제목
        today = datetime.datetime.now().strftime("%Y-%m-%d")
        draw.text((80, 180), "오늘의 모닝 브리핑", fill=(255, 255, 255), font=title_font)
        draw.text((80, 280), f"날짜: {today}", fill=(148, 163, 184), font=subtitle_font)
        draw.line((80, 360, 1000, 360), fill=(51, 65, 85), width=3)

        # 1. 주요 지수 (US Market)
        y_offset = 450
        draw.text((80, y_offset), "[미 증시 마감 기록]", fill=(56, 189, 248), font=subtitle_font)
        y_offset += 100
        
        for name, data in indices.items():
            color = (248, 113, 113) if data['change'] < 0 else (74, 222, 128)
            symbol = "-" if data['change'] < 0 else "+"
            
            # 수치 정보
            draw.text((100, y_offset), f"{name}", fill=(241, 245, 249), font=content_font)
            price_text = f"{data['price']} ({symbol}{abs(data['pct'])}%)"
            draw.text((500, y_offset), price_text, fill=color, font=content_font)
            y_offset += 90

        # 2. 빅테크 동향
        y_offset += 100
        draw.text((80, y_offset), "[주요 기술주 등락]", fill=(56, 189, 248), font=subtitle_font)
        y_offset += 100
        
        # 2개씩 한 줄에 배치
        items = list(stocks.items())
        for i in range(0, len(items), 2):
            for j in range(2):
                if i + j < len(items):
                    name, pct = items[i + j]
                    color = (248, 113, 113) if pct < 0 else (74, 222, 128)
                    symbol = "-" if pct < 0 else "+"
                    x_pos = 100 + (j * 450)
                    draw.text((x_pos, y_offset), f"{name}", fill=(241, 245, 249), font=content_font)
                    draw.text((x_pos + 220, y_offset), f"{symbol}{abs(pct)}%", fill=color, font=content_font)
            y_offset += 90

        # 하단 장식
        draw.line((80, 1750, 1000, 1750), fill=(51, 65, 85), width=2)
        draw.text((80, 1780), "출근길 빠른 투자 정보 - 밸류 픽셀", fill=(148, 163, 184), font=small_font)

        # 파일 저장
        output_path = os.path.join(self.output_dir, f"report_{today}.png")
        image.save(output_path)
        print(f"Report image saved: {output_path}")
        return output_path

    def run(self):
        print("Collecting market data...")
        indices = self.get_market_indices()
        stocks = self.get_top_stocks()
        
        print("Generating visual report...")
        image_path = self.generate_report_image(indices, stocks)
        
        report_data = {
            "date": datetime.datetime.now().isoformat(),
            "indices": indices,
            "stocks": stocks,
            "image_path": image_path
        }
        
        with open(os.path.join(self.output_dir, "latest_report.json"), "w", encoding="utf-8") as f:
            json.dump(report_data, f, ensure_ascii=False, indent=4)
            
        print("Latest report data updated.")
        return report_data

if __name__ == "__main__":
    collector = MarketCollector()
    collector.run()
